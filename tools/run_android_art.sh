#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-only
set -euo pipefail

art_expected_api="${1:?Pass the emulator API level}"
art_consumer_version="${2-}"
art_maven_repository="${3-}"
task_repo_root="$(cd "$(dirname "$0")/.." && pwd)"
cd "$task_repo_root"
mkdir -p build/art-evidence
trap 'adb logcat -d > build/art-evidence/logcat.txt 2>&1 || true' EXIT

adb shell getprop > build/art-evidence/device-properties.txt
adb shell uname -a > build/art-evidence/kernel.txt
adb devices -l > build/art-evidence/devices.txt
art_actual_api="$(adb shell getprop ro.build.version.sdk | tr -d '\r')"
if [[ "$art_actual_api" != "$art_expected_api" ]]; then
    printf 'Expected emulator API %s, found %s\n' "$art_expected_api" "$art_actual_api" >&2
    exit 1
fi
adb logcat -c

./gradlew --no-daemon --console=plain -PenableAndroid=true \
    -Pandroid.testInstrumentationRunnerArguments.expectedApi="$art_expected_api" \
    connectedDebugAndroidTest

if [[ -n "$art_consumer_version" || -n "$art_maven_repository" ]]; then
    : "${art_consumer_version:?Pass both consumer version and repository}"
    : "${art_maven_repository:?Pass both consumer version and repository}"
    ./gradlew --no-daemon --console=plain -p integration/consumer \
        -PenableAndroid=true -Pspake2Version="$art_consumer_version" \
        -Pspake2Repository="$art_maven_repository" \
        -Pandroid.testInstrumentationRunnerArguments.expectedApi="$art_expected_api" \
        connectedDebugAndroidTest
    python3 tools/check_android_art_results.py --api "$art_expected_api" \
        --consumer-root integration/consumer --consumer-version "$art_consumer_version"
else
    python3 tools/check_android_art_results.py --api "$art_expected_api"
fi
