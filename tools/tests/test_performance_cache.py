#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-only
"""The performance harness must select JVM bytecode, independently of cache order."""
import importlib.util
import os
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch


SCRIPT = Path(__file__).resolve().parents[1] / "run_jvm_performance.py"
spec = importlib.util.spec_from_file_location("run_jvm_performance", SCRIPT)
performance = importlib.util.module_from_spec(spec)
spec.loader.exec_module(performance)


class CompilerInvocationCaptured(Exception):
    pass


class PerformanceCacheTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.cache = self.root / "gradle"
        self.files = self.cache / "caches/modules-2/files-2.1"
        self.coordinates = [
            ("org.jetbrains.kotlin", "kotlin-compiler-embeddable", "2.2.21"),
            ("org.jetbrains.kotlin", "kotlin-stdlib", "2.2.21"),
            ("org.jetbrains.kotlin", "kotlin-script-runtime", "2.2.21"),
            ("org.jetbrains.kotlin", "kotlin-daemon-embeddable", "2.2.21"),
            ("org.jetbrains.kotlin", "kotlin-reflect", "1.6.10"),
            ("org.jetbrains.kotlinx", "kotlinx-coroutines-core-jvm", "1.8.0"),
            ("org.jetbrains", "annotations", "23.0.0"),
        ]
        self.jars = {}
        for group, artifact, version in self.coordinates:
            self.jars[artifact] = self.add(group, artifact, version, "binary", f"{artifact}-{version}.jar")

    def add(self, group, artifact, version, cache_hash, name):
        path = self.files / group / artifact / version / cache_hash / name
        path.parent.mkdir(parents=True, exist_ok=True)
        # No Java is executed in these tests; the intercepted compiler command
        # is the observable contract, not a reimplementation of jar selection.
        path.write_bytes(b"test-only cache entry")
        return path

    def compiler_command(self, classifier_first=False):
        commands = []
        def capture(command, **kwargs):
            commands.append(command)
            raise CompilerInvocationCaptured()
        original_glob = Path.glob
        def reordered_glob(path, pattern):
            # Both orders are legal for an unsorted filesystem glob. Force the
            # observed failure order so the regression is filesystem-independent.
            values = list(original_glob(path, pattern))
            return iter(sorted(values, key=lambda item: (not item.name.endswith(("-all.jar", "-sources.jar")), str(item))))
        argv = [str(SCRIPT), "--gradle-cache", str(self.cache), "--java", "test-only-java",
                "--output", str(self.root / "output"), "--warmups", "0", "--measurements", "1"]
        with patch.object(sys, "argv", argv), patch.object(performance.subprocess, "run", side_effect=capture):
            if classifier_first:
                with patch.object(Path, "glob", reordered_glob), self.assertRaises(CompilerInvocationCaptured):
                    performance.main()
            else:
                with self.assertRaises(CompilerInvocationCaptured):
                    performance.main()
        self.assertEqual(1, len(commands))
        return commands[0]

    def test_complete_canonical_cache_is_accepted(self):
        command = self.compiler_command()
        self.assertEqual(set(map(str, self.jars.values())), set(command[2].split(os.pathsep)))
        self.assertEqual(str(self.jars["kotlin-stdlib"]), command[command.index("-classpath") + 1])

    def test_stdlib_metadata_and_sources_are_never_selected(self):
        unwanted = [self.add("org.jetbrains.kotlin", "kotlin-stdlib", "2.2.21", kind,
                             f"kotlin-stdlib-2.2.21-{kind}.jar") for kind in ("all", "sources")]
        command = self.compiler_command(classifier_first=True)
        self.assertIn(str(self.jars["kotlin-stdlib"]), command[2].split(os.pathsep))
        self.assertEqual(str(self.jars["kotlin-stdlib"]), command[command.index("-classpath") + 1])
        for path in unwanted:
            self.assertNotIn(str(path), command[2].split(os.pathsep))

    def test_annotations_sources_are_never_selected(self):
        unwanted = self.add("org.jetbrains", "annotations", "23.0.0", "zz-classifier", "annotations-23.0.0-sources.jar")
        command = self.compiler_command()
        self.assertIn(str(self.jars["annotations"]), command[2].split(os.pathsep))
        self.assertNotIn(str(unwanted), command[2].split(os.pathsep))

    def test_only_classifiers_do_not_satisfy_missing_jvm_jar(self):
        self.jars["kotlin-stdlib"].unlink()
        self.add("org.jetbrains.kotlin", "kotlin-stdlib", "2.2.21", "metadata", "kotlin-stdlib-2.2.21-all.jar")
        with self.assertRaisesRegex(SystemExit, "Missing cached kotlin-stdlib:2.2.21"):
            self.compiler_command()

    def test_ambiguous_canonical_jars_are_rejected(self):
        self.add("org.jetbrains.kotlin", "kotlin-stdlib", "2.2.21", "different-hash", "kotlin-stdlib-2.2.21.jar")
        with self.assertRaisesRegex(SystemExit, "Ambiguous cached kotlin-stdlib:2.2.21"):
            self.compiler_command()


if __name__ == "__main__":
    unittest.main()
