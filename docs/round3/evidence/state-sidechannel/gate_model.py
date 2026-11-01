#!/usr/bin/env python3
"""Bounded SC exploration of delayed failed-acquisition cleanup.

This is an independently written abstract model, not execution of Kotlin.
One live owner has already acquired gate=1. One or two contenders have already
failed their initial CAS while it was live. Every remaining load, CAS, erasure,
publication and return may interleave. Success and ordinary owner failure are
explored separately. 'old_single_cas' is a deliberate regression control for
the historical missed-release race, not a description of current source.
"""
from dataclasses import dataclass, replace
from collections import deque
import hashlib
import json
from pathlib import Path
import platform
import subprocess
import sys


@dataclass(frozen=True)
class State:
    gate: int = 1
    owner: str = "work"
    contenders: tuple = ()
    erased: bool = False
    published: bool = False
    poisoned: bool = False
    output_erased: bool = False


def successors(s, ordinary_failure, old_single_cas):
    # Buffer writes precede the owner's release CAS. No mutation follows it.
    if s.owner == "work":
        yield "owner: finish operation", replace(s, owner="erase" if ordinary_failure else "release")
    elif s.owner == "release":
        if s.gate == 1:
            yield "owner: CAS 1->0; publish owned output", replace(s, gate=0, owner="done", published=True)
        else:
            yield "owner: failed CAS 1->0", replace(s, owner="wipe_output")
    elif s.owner == "wipe_output":
        yield "owner: wipe rejected output", replace(s, output_erased=True, owner="erase")
    elif s.owner == "erase":
        yield "owner: erase buffers", replace(s, erased=True, owner="terminal")
    elif s.owner == "terminal":
        yield "owner: publish gate=3", replace(s, gate=3, owner="done")

    for i, pc in enumerate(s.contenders):
        if pc == "done":
            continue
        next_pc = None
        changes = {}
        if pc == "load":
            next_pc = "cas1" if old_single_cas else ({0: "cas0", 1: "cas1"}.get(s.gate, "done"))
            event = f"contender {i}: load gate={s.gate}"
        elif pc == "cas0":
            event = f"contender {i}: CAS 0->4"
            if s.gate == 0:
                changes["gate"] = 4
                next_pc = "erase"
            else:
                next_pc = "load"
        elif pc == "cas1":
            event = f"contender {i}: CAS 1->2"
            if s.gate == 1:
                changes.update(gate=2, poisoned=True)
                next_pc = "done"
            else:
                next_pc = "done" if old_single_cas else "load"
        elif pc == "erase":
            event = f"contender {i}: erase buffers under gate=4"
            changes["erased"] = True
            next_pc = "terminal"
        elif pc == "terminal":
            event = f"contender {i}: publish gate=3"
            changes["gate"] = 3
            next_pc = "done"
        else:
            raise RuntimeError(pc)
        pcs = list(s.contenders)
        pcs[i] = next_pc
        yield event, replace(s, contenders=tuple(pcs), **changes)


def violation(s):
    if s.gate == 3 and not s.erased:
        return "terminal gate visible before buffer erasure"
    if s.gate == 4 and s.owner not in ("done",):
        return "idle cleanup overlaps active owner"
    if s.published and s.poisoned:
        return "poisoned owner published output"
    done = s.owner == "done" and all(pc == "done" for pc in s.contenders)
    if done:
        if s.gate != 3 or not s.erased:
            return "failed acquisition left session usable"
        if s.poisoned and not s.output_erased and s.owner == "done":
            # An ordinary failure has no return buffer. Checked separately below.
            return "possible rejected-output wipe gap"
    return None


def explore(count, ordinary_failure=False, old_single_cas=False):
    start = State(contenders=("load",) * count)
    queue = deque([start])
    paths = {start: []}
    edges = 0
    finals = 0
    failures = []
    while queue:
        s = queue.popleft()
        error = violation(s)
        if ordinary_failure and error == "possible rejected-output wipe gap":
            error = None
        if error:
            failures.append({"reason": error, "trace": paths[s], "state": vars(s)})
        if s.owner == "done" and all(pc == "done" for pc in s.contenders):
            finals += 1
        for event, target in successors(s, ordinary_failure, old_single_cas):
            edges += 1
            if target not in paths:
                paths[target] = paths[s] + [event]
                queue.append(target)
    return {"contenders": count, "owner_ordinary_failure": ordinary_failure,
            "old_single_cas_control": old_single_cas, "reachable_states": len(paths),
            "edges": edges, "terminal_states": finals, "violations": failures}


def main():
    root = Path(__file__).resolve().parents[4]
    current = [explore(n, failure) for n in (1, 2) for failure in (False, True)]
    control = explore(1, False, True)
    files = ["src/commonMain/kotlin/io/github/flyfish233/spake2/rfc/Rfc9382Session.kt",
             "legacy/src/commonMain/kotlin/io/github/flyfish233/spake2/legacy/LegacySpake25519.kt"]
    report = {
        "kind": "bounded abstract model, not Kotlin execution or a formal refinement proof",
        "source_commit": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=root, text=True).strip(),
        "source_sha256": {p: hashlib.sha256((root / p).read_bytes()).hexdigest() for p in files},
        "environment": {"python": sys.version, "platform": platform.platform()},
        "assumptions": ["sequentially consistent atomic load/CAS/store", "finite contenders already failed initial acquisition",
                        "owner ordinary completion or exception; no process crash/OOM or malicious buffer alias",
                        "owned output does not alias session storage", "erasure and operation body abstracted as single steps"],
        "current_model": current,
        "historical_race_negative_control": control,
        "pass": all(not case["violations"] for case in current) and bool(control["violations"]),
    }
    out = Path(__file__).with_name("gate-model-result.json")
    out.write_text(json.dumps(report, indent=2) + "\n")
    print(json.dumps({"pass": report["pass"], "current_reachable_states": sum(c["reachable_states"] for c in current),
                      "current_violations": sum(len(c["violations"]) for c in current),
                      "negative_control_violations": len(control["violations"]), "report": str(out)}, indent=2))
    return 0 if report["pass"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
