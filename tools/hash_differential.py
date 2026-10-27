#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-only
"""Compile commonMain hash sources and compare with independent Python expectations.

This optional bootstrap check uses the Kotlin compiler bundled with Gradle. It
does not change the project toolchain or add a production dependency. Example:
python3 tools/hash_differential.py --gradle-home /path/to/gradle-8.14.3
"""
import argparse
import hashlib
import hmac
import pathlib
import subprocess
import tempfile


def data(length, offset=17):
    return bytes((i * 131 + offset) % 256 for i in range(length))


def hkdf(ikm, salt, info, length):
    prk = hmac.digest(salt, ikm, "sha256")
    previous = output = b""
    for counter in range(1, (length + 31) // 32 + 1):
        previous = hmac.digest(prk, previous + info + bytes([counter]), "sha256")
        output += previous
    return output[:length]


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--gradle-home", type=pathlib.Path, required=True)
    p.add_argument("--java", default="java")
    args = p.parse_args()
    root = pathlib.Path(__file__).resolve().parents[1]
    lib = args.gradle_home / "lib"
    stdlib = next(lib.glob("kotlin-stdlib-*.jar"))
    prefix = """package io.github.flyfish233.spake2.internal.crypto
fun h(s:String):ByteArray=ByteArray(s.length/2){s.substring(2*it,2*it+2).toInt(16).toByte()}
fun data(n:Int,offset:Int=17):ByteArray=ByteArray(n){(it*131+offset).toByte()}
fun same(a:ByteArray,b:ByteArray,label:String){check(a.contentEquals(b)){label}}
fun main(){
"""
    lines = [prefix]
    count = 0
    lengths = list(range(257)) + [511, 512, 513, 1023, 1024, 1025, 4095, 4096, 4097, 65535]
    for n in lengths:
        for name in ("sha256", "sha512"):
            expected = getattr(hashlib, name)(data(n)).hexdigest()
            kt = "Sha256" if name == "sha256" else "Sha512"
            lines.append(f'same(h("{expected}"),{kt}.digest(data({n})),"{name} length {n}")')
            count += 1
    for key_length in [0, 1, 16, 31, 32, 63, 64, 65, 127, 128, 131]:
        for msg_length in [0, 1, 55, 56, 64, 128, 1024]:
            expected = hmac.digest(data(key_length), data(msg_length, 23), "sha256").hex()
            lines.append(f'same(h("{expected}"),HmacSha256.mac(data({key_length}),data({msg_length},23)),"HMAC {key_length}/{msg_length}")')
            count += 1
    for length in [0, 1, 16, 31, 32, 33, 63, 64, 65, 255, 256, 257, 1024, 8159, 8160]:
        for info_length in [0, 1, 64, 129]:
            expected = hkdf(data(31), data(65), data(info_length), length)
            # Hashing output here keeps the generated method within JVM code-size limits.
            expected_digest = hashlib.sha256(expected).hexdigest()
            lines.append(f'same(h("{expected_digest}"),Sha256.digest(HkdfSha256.derive(data(31),data(65),data({info_length}),{length})),"HKDF {length}/{info_length}")')
            count += 1
    lines.append(f'println("PASS: {count} independent hashlib/hmac differential comparisons")\n}}')
    with tempfile.TemporaryDirectory(prefix="spake2-hash-check-") as temporary:
        directory = pathlib.Path(temporary)
        source = directory / "HashDifferential.kt"
        source.write_text("\n".join(lines))
        jar = directory / "hashes.jar"
        source_root = root / "primitives/src/commonMain/kotlin/io/github/flyfish233/spake2/internal/crypto"
        if not source_root.exists():
            source_root = root / "src/commonMain/kotlin/io/github/flyfish233/spake2/internal/crypto"
        sources = sorted(source_root.glob("*.kt"))
        opt_in = root / "primitives/src/commonMain/kotlin/io/github/flyfish233/spake2/InternalSpake2Api.kt"
        if opt_in.exists():
            sources.append(opt_in)
        assert sources, "No production Kotlin hash sources found"
        compiler = [args.java, "-cp", str(lib / "*"), "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler"]
        subprocess.run(compiler + ["-version"], check=True)
        subprocess.run(compiler + ["-no-stdlib", "-no-reflect", "-opt-in=io.github.flyfish233.spake2.InternalSpake2Api", "-classpath", str(stdlib),
                                    "-d", str(jar)] + [str(x) for x in sources] + [str(source)], check=True)
        subprocess.run([args.java, "-cp", str(jar) + ":" + str(stdlib),
                        "io.github.flyfish233.spake2.internal.crypto.HashDifferentialKt"], check=True)


if __name__ == "__main__":
    main()
