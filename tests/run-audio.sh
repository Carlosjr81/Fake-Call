#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
jarvis_test_dir="$(mktemp -d)"
trap 'rm -rf "$jarvis_test_dir"' EXIT
javac -d "$jarvis_test_dir" android/app/src/main/java/br/com/jarvis/voice/VoiceDsp.java android/app/src/main/java/br/com/jarvis/voice/Wav.java tests/DspChecks.java
java -cp "$jarvis_test_dir" DspChecks
