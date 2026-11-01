#!/usr/bin/env python3
"""Bounded affine/algebraic review probe; does not execute Kotlin or BoringSSL.

Uses Python integers and affine Edwards formulas, independently of the Kotlin
extended-coordinate and limb code. All secrets are public test fixtures.
"""
import datetime
import hashlib
import json
import pathlib
import platform
import subprocess
import sys

HERE = pathlib.Path(__file__).resolve().parent
ROOT = HERE.parents[3]
P = 2**255 - 19
L = 2**252 + 27742317777372353535851937790883648493
D = -121665 * pow(121666, -1, P) % P
IDENTITY = (0, 1)


def decode(raw):
    if len(raw) != 32:
        raise ValueError("point length")
    value = int.from_bytes(raw, "little")
    sign = value >> 255
    y = (value & (2**255 - 1)) % P
    x2 = (y*y - 1) * pow(D*y*y + 1, -1, P) % P
    x = pow(x2, (P+3)//8, P)
    if x*x % P != x2:
        x = x * pow(2, (P-1)//4, P) % P
    if x*x % P != x2:
        raise ValueError("not on curve")
    if (x & 1) != sign:
        x = -x % P
    return x, y


def encode(point):
    x, y = point
    return (y | ((x & 1) << 255)).to_bytes(32, "little")


def add(left, right):
    x1, y1 = left
    x2, y2 = right
    term = D*x1*x2*y1*y2 % P
    return ((x1*y2+y1*x2)*pow(1+term, -1, P) % P,
            (y1*y2+x1*x2)*pow(1-term, -1, P) % P)


def multiply(point, scalar):
    out = IDENTITY
    while scalar:
        if scalar & 1:
            out = add(out, point)
        point = add(point, point)
        scalar >>= 1
    return out


BASE = decode(bytes.fromhex("5866666666666666666666666666666666666666666666666666666666666666"))
M = decode(bytes.fromhex("5ada7e4bf6ddd9adb6626d32131c6b5c51a1e347a3478f53cfcf441b88eed12e"))
N = decode(bytes.fromhex("10e3df0ae37d8e7a99b5fe74b44672103dbddcbd06af680d71329a11693bc778"))


def require(condition, reason):
    if not condition:
        raise ValueError(reason)


def generate(endpoint):
    ph = hashlib.sha512(bytes.fromhex(endpoint["passwordHex"])).digest()
    reduced = int.from_bytes(ph, "little") % L
    # Closed-form unique representative congruent to s mod L and zero mod 8.
    ps = reduced if endpoint["disablePasswordScalarHack"] else reduced + ((-reduced * pow(L, -1, 8)) % 8) * L
    ephemeral = 8 * (int.from_bytes(bytes.fromhex(endpoint["generate"]["privateInput64Hex"]), "little") % L)
    own_mask = M if endpoint["role"] == "alice" else N
    message = encode(add(multiply(BASE, ephemeral), multiply(own_mask, ps)))
    return {"ph": ph, "ps": ps, "reduced": reduced, "e": ephemeral, "msg": message,
            "own": bytes.fromhex(endpoint["myNameHex"]), "peer": bytes.fromhex(endpoint["theirNameHex"]),
            "role": endpoint["role"]}


def process(state, peer_raw):
    q = decode(peer_raw)
    peer_mask = multiply(N if state["role"] == "alice" else M, state["ps"])
    unmasked = add(q, (-peer_mask[0] % P, peer_mask[1]))
    shared = multiply(unmasked, state["e"])
    if state["role"] == "alice":
        fields = [state["own"], state["peer"], state["msg"], peer_raw]
    else:
        fields = [state["peer"], state["own"], peer_raw, state["msg"]]
    fields += [encode(shared), state["ph"]]
    transcript = b"".join(len(f).to_bytes(8, "little") + f for f in fields)
    return {"keyHex": hashlib.sha512(transcript).hexdigest(), "dhSharedHex": encode(shared).hex(),
            "peerUnmaskedHex": encode(unmasked).hex()}


def main():
    fixture_paths = [ROOT/"test-vectors/legacy"/p for p in [
        "boringssl-spake2-vectors.json", "fresh-boringssl-oracle.json", "boringssl-spake2-fuzz-vectors.jsonl"]]
    fixtures = json.loads(fixture_paths[0].read_text())
    fresh = json.loads(fixture_paths[1].read_text())
    checks = []
    high_bits = []
    for exchange in fixtures["exchanges"]:
        for role in ["alice", "bob"]:
            endpoint = exchange[role]
            state = generate(endpoint)
            actual_fields = {"privateScalarHex": state["e"].to_bytes(32,"little").hex(),
                             "passwordScalarHex": state["ps"].to_bytes(32,"little").hex(),
                             "passwordHashHex": state["ph"].hex(), "messageHex": state["msg"].hex()}
            for key, actual in actual_fields.items():
                require(actual == endpoint["generate"][key], f"{exchange['id']} {role} {key}")
            expected = exchange[role+"Process"]
            result = process(state, bytes.fromhex(expected["theirMessageHex"]))
            require(all(result[k] == expected[k] for k in result), f"process {exchange['id']} {role}")
            checks.append({"id":exchange["id"], "role":role,"result":"PASS"})
            if state["e"] >> 255:
                high_bits.append({"id":exchange["id"],"role":role,"privateScalarHex":actual_fields["privateScalarHex"]})
    normal = generate(fixtures["exchanges"][0]["alice"])
    peer_records = []
    for case in fixtures["processCases"]:
        expected = case["process"]
        try:
            result = process(normal, bytes.fromhex(expected["theirMessageHex"]))
            success = True
        except ValueError:
            result = {"keyHex":""}
            success = False
        require(success == expected["success"], case["id"]+" status")
        require(all(result[k] == expected[k] for k in result), case["id"]+" fields")
        peer_records.append({"id":case["id"],"accepted":success,"result":"PASS"})
    for line in fresh["freshPeerCases"]:
        cid, peer, accepted, key = line.split("|")
        try:
            actual = process(normal,bytes.fromhex(peer))["keyHex"]
            success = True
        except ValueError:
            success, actual = False, ""
        require(success == (accepted == "1") and actual == key, cid)
        peer_records.append({"id":cid,"accepted":success,"result":"PASS"})

    # Attack only needs a public message and known parameters. Select one
    # historical unsafe Bob endpoint per residue, plus hardened controls, then
    # recover the reduced scalar modulo 8 from [L] message.
    witnesses = {}
    alice_witnesses = {}
    controls = []
    for line in fixture_paths[2].read_text().splitlines():
        case = json.loads(line)
        residue = int.from_bytes(hashlib.sha512(bytes.fromhex(case["passwordHex"])).digest(),"little") % L % 8
        for role, mask in [("alice",M),("bob",N)]:
            if role == "bob" and case[role+"DisablePasswordScalarHack"] and residue not in witnesses:
                witnesses[residue] = (case,role,mask)
            if role == "alice" and case[role+"DisablePasswordScalarHack"] and residue % 2 not in alice_witnesses:
                alice_witnesses[residue % 2] = case
            elif not case[role+"DisablePasswordScalarHack"] and len(controls)<8:
                controls.append((case,role))
        if len(witnesses)==7 and len(controls)==8 and len(alice_witnesses)==2:
            break
    # The retained 512-exchange corpus has no unsafe Bob endpoint in residue 5.
    # Keep that coverage limit explicit rather than inventing a C execution.
    require(set(witnesses)=={0,1,2,3,4,6,7} and len(controls)==8,"projection controls")
    leak=[]
    for expected,(case,role,mask) in sorted(witnesses.items()):
        generator=multiply(mask,L)
        table={encode(multiply(generator,i)):i for i in range(8)}
        require(len(table)==8,"order-eight mask projection")
        message=bytes.fromhex(case[role+"MessageHex"])
        projection=encode(multiply(decode(message),L))
        recovered=table[projection]
        require(recovered==expected,"unsafe residue recovery")
        leak.append({"fixtureIndex":case["index"],"role":role,"messageHex":message.hex(),
                     "projectionHex":projection.hex(),"recoveredReducedScalarMod8":recovered})
    control_results=[]
    alice_leak=[]
    alice_generator=multiply(M,L)
    alice_table={encode(multiply(alice_generator,i)):i for i in range(2)}
    require(len(alice_table)==2 and multiply(alice_generator,2)==IDENTITY,"Alice mask projection order two")
    for expected,case in sorted(alice_witnesses.items()):
        message=bytes.fromhex(case["aliceMessageHex"])
        projection=encode(multiply(decode(message),L))
        recovered=alice_table[projection]
        require(recovered==expected,"unsafe Alice parity recovery")
        alice_leak.append({"fixtureIndex":case["index"],"role":"alice","messageHex":message.hex(),
                          "projectionHex":projection.hex(),"recoveredReducedScalarMod2":recovered})
    for case,role in controls:
        projection=encode(multiply(decode(bytes.fromhex(case[role+"MessageHex"])),L))
        require(projection==encode(IDENTITY),"hardened torsion leak")
        control_results.append({"fixtureIndex":case["index"],"role":role,"projectionHex":projection.hex()})
    positive_zero=bytes.fromhex("01"+"00"*31)
    negative_zero=positive_zero[:-1]+b"\x80"
    require(decode(positive_zero)==decode(negative_zero),"same decoded identity")
    positive_key=process(normal,positive_zero)["keyHex"]
    negative_key=process(normal,negative_zero)["keyHex"]
    require(positive_key!=negative_key,"raw encoding must remain transcript-bound")
    result={"executedAtUtc":datetime.datetime.now(datetime.timezone.utc).isoformat(),
            "sourceCommit":subprocess.check_output(["git","rev-parse","HEAD"],cwd=ROOT,text=True).strip(),
            "python":sys.version,"platform":platform.platform(),
            "scope":"New Python affine/algebraic execution over retained C-oracle fixtures; not a new C or Kotlin execution",
            "deterministicEndpointChecks":checks,"highBitEndpoints":high_bits,
            "peerChecks":peer_records,"unsafeModeProjectionWitnesses":leak,
            "unsafeAliceParityWitnesses":alice_leak,
            "maskProjectionOrders":{"M":2,"N":8},
            "hardenedProjectionControls":control_results,
            "negativeZeroTranscriptBinding":{"sameDecodedPoint":True,"differentKeys":True,
                                              "positiveKey":positive_key,"negativeKey":negative_key},
            "fixtureSha256":{p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in fixture_paths},
            "probeSha256":hashlib.sha256(pathlib.Path(__file__).read_bytes()).hexdigest(),"result":"PASS"}
    (HERE/"independent-legacy-probe.json").write_text(json.dumps(result,indent=2)+"\n")
    print(json.dumps({"result":"PASS","deterministicEndpoints":len(checks),"highBitEndpoints":len(high_bits),
                      "peerCases":len(peer_records),"unsafeResiduesRecovered":len(leak),
                      "unsafeAliceParityRecovered":len(alice_leak),
                      "hardenedControls":len(control_results),"samePointDifferentTranscriptKeys":True},indent=2))


if __name__ == "__main__":
    main()
