#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-only
# Copyright (c) 2026 Flyfish233 and spake2 contributors
"""Re-execute pinned BoringSSL against historical fixtures and generate new ones.

Build tools/legacy_oracle.cc with the source tree at the recorded BoringSSL
commit, -DOPENSSL_NO_ASM, and the matching libcrypto.a. No Kotlin code is used.
The executable path is explicit to avoid accidentally invoking another oracle.
"""
import argparse
import hashlib
import json
import subprocess
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
VECTORS = ROOT / 'test-vectors/legacy'
BORINGSSL = '9e04aed2d1652441d2c84e85ed21acc05e053260'


def run(oracle, rows):
    process = subprocess.run([str(oracle)], input='\n'.join('|'.join(row) for row in rows) + '\n', text=True, capture_output=True, check=True)
    result = [line.split('|') for line in process.stdout.splitlines()]
    assert len(result) == len(rows)
    return result


def row(role, own, peer, password, entropy, unsafe, message):
    return [role, own, peer, password, entropy, str(int(unsafe)), message]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--oracle', type=Path, required=True)
    args = parser.parse_args()
    oracle = args.oracle.resolve()
    legacy = json.loads((VECTORS / 'boringssl-spake2-vectors.json').read_text())
    historical = [json.loads(line) for line in (VECTORS / 'boringssl-spake2-fuzz-vectors.jsonl').read_text().splitlines()]
    requests, expected = [], []
    for exchange in legacy['exchanges']:
        for name, other in [('alice', 'bob'), ('bob', 'alice')]:
            p, q = exchange[name], exchange[other]
            g, result = p['generate'], exchange[name + 'Process']
            requests.append(row(name, p['myNameHex'], p['theirNameHex'], p['passwordHex'], g['privateInput64Hex'], p['disablePasswordScalarHack'], q['generate']['messageHex']))
            expected.append([g['messageHex'], g['privateScalarHex'], g['passwordScalarHex'], g['passwordHashHex'], str(int(result['success'])), result['keyHex']])
    for vector in historical:
        for name, other in [('alice', 'bob'), ('bob', 'alice')]:
            requests.append(row(name, vector[name+'NameHex'], vector[other+'NameHex'], vector['passwordHex'], vector[name+'PrivateInput64Hex'], vector[name+'DisablePasswordScalarHack'], vector[other+'MessageHex']))
            expected.append([vector[name+'MessageHex'], None, None, None, str(int(vector[name+'Success'])), vector[name+'KeyHex']])
    for i, (observed, wanted) in enumerate(zip(run(oracle, requests), expected)):
        for field, (actual, goal) in enumerate(zip(observed, wanted)):
            assert goal is None or actual == goal, (i, field, actual, goal)
    normal = legacy['exchanges'][0]['alice']
    requests = [row('alice', normal['myNameHex'], normal['theirNameHex'], normal['passwordHex'], normal['generate']['privateInput64Hex'], False, c['process']['theirMessageHex']) for c in legacy['processCases']]
    for case, result in zip(legacy['processCases'], run(oracle, requests)):
        assert result[4:] == [str(int(case['process']['success'])), case['process']['keyHex']], case['id']

    fresh_requests = []
    for index in range(24):
        def material(label, length):
            return hashlib.shake_256(f'spake2-independent-oracle-2026:{index}:{label}'.encode()).digest(length).hex()
        pw = material('password', [0, 1, 16, 63, 64, 65, 128, 255][index % 8])
        a, b = material('alice-identity', index), material('bob-identity', 23-index)
        fresh_requests += [row('alice', a, b, pw, material('a-entropy', 64), index % 3 == 0, ''),
                           row('bob', b, a, pw, material('b-entropy', 64), index % 3 == 1, '')]
    generated = run(oracle, fresh_requests)
    for i in range(0, len(fresh_requests), 2):
        fresh_requests[i][6] = generated[i+1][0]
        fresh_requests[i+1][6] = generated[i][0]
    fresh_results = run(oracle, fresh_requests)
    fresh_exchanges = []
    for i in range(0, len(fresh_requests), 2):
        a, b = fresh_requests[i:i+2]
        ar, br = fresh_results[i:i+2]
        assert ar[4] == br[4] == '1' and ar[5] == br[5]
        fresh_exchanges.append('|'.join([f'fresh-{i//2}', a[3], b[3], a[1], b[1], a[4], b[4], a[5], b[5], ar[0], br[0], ar[5], br[5]]))

    # Non-canonical y, x=0 sign variants, and seeded arbitrary encodings.
    prime = 2**255 - 19
    peer_encodings = [(prime + i + (sign << 255)).to_bytes(32, 'little').hex() for i in range(19) for sign in range(2)]
    peer_encodings += [(y + (sign << 255)).to_bytes(32, 'little').hex() for y in [0, 1, prime-1] for sign in range(2)]
    peer_encodings += [hashlib.sha256(f'spake2-malformed-peer:{i}'.encode()).hexdigest() for i in range(64)]
    requests = [row('alice', normal['myNameHex'], normal['theirNameHex'], normal['passwordHex'], normal['generate']['privateInput64Hex'], False, peer) for peer in peer_encodings]
    peer_results = run(oracle, requests)
    fresh_peers = ['|'.join([f'fresh-peer-{i}', peer, result[4], result[5]]) for i, (peer, result) in enumerate(zip(peer_encodings, peer_results))]
    output = {
        'oracle': 'tools/legacy_oracle.cc with pinned BoringSSL, entropy substitution only',
        'boringsslCommit': BORINGSSL,
        'freshExchanges': fresh_exchanges,
        'freshPeerCases': fresh_peers,
    }
    (VECTORS / 'fresh-boringssl-oracle.json').write_text(json.dumps(output, indent=2) + '\n')
    report = {
        'executedAtUtc': datetime.now(timezone.utc).isoformat(),
        'boringsslCommit': BORINGSSL,
        'harnessSha256': hashlib.sha256((ROOT / 'tools/legacy_oracle.cc').read_bytes()).hexdigest(),
        'oracleExecutableSha256': hashlib.sha256(oracle.read_bytes()).hexdigest(),
        'historicalFuzzExchangesReexecuted': len(historical),
        'historicalDeterministicExchangesReexecuted': len(legacy['exchanges']),
        'historicalPeerCasesReexecuted': len(legacy['processCases']),
        'freshExchanges': len(fresh_exchanges),
        'freshPeerCases': len(fresh_peers),
        'freshPeerAccepted': sum(r[4] == '1' for r in peer_results),
        'result': 'all historical output bytes matched',
        'inputSha256': {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in VECTORS.glob('boringssl-*')},
    }
    (VECTORS / 'oracle-regeneration-report.json').write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps(report, indent=2))


if __name__ == '__main__':
    main()
