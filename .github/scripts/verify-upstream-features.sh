#!/usr/bin/env bash
# Run after verify-tv-ui.sh has installed the isolated preview and instrumentation APKs.
set -euo pipefail
classes="${1:?Pass the comma-separated regression test classes}"
out=artifacts/upstream-features
mkdir -p "$out"
read_package() {
    python3 - "$1" <<'PY'
import json, re, sys
package = json.load(open(sys.argv[1]))['applicationId']
assert re.fullmatch(r'[a-zA-Z][a-zA-Z0-9_]*(?:\.[a-zA-Z][a-zA-Z0-9_]*)+', package)
print(package)
PY
}
pkg=$(read_package app/build/outputs/apk/leanbackArm64_v8a/preview/output-metadata.json)
test_pkg=$(read_package app/build/outputs/apk/androidTest/leanbackArm64_v8a/preview/output-metadata.json)
collect() {
    for directory in upstream-verification spider-dialog-verification folder-filter-verification; do
        adb pull "/sdcard/Android/data/$pkg/files/$directory" "$out/" >/dev/null 2>&1 || true
    done
    adb logcat -b all -d -v threadtime > "$out/logcat.txt" 2>&1 || true
    adb exec-out screencap -p > "$out/last-screen.png" 2>/dev/null || true
    adb shell dumpsys activity exit-info "$pkg" > "$out/exit-info.txt" 2>&1 || true
}
trap collect EXIT
adb logcat -c
adb shell am instrument -w -r -e class "$classes" "$test_pkg/androidx.test.runner.AndroidJUnitRunner" | tee "$out/instrumentation.txt"
python3 - "$out/instrumentation.txt" <<'PY'
from pathlib import Path
import re, sys
result = Path(sys.argv[1]).read_text()
if not re.search(r'^OK \([1-9][0-9]* tests?\)', result, re.M):
    raise SystemExit('Upstream feature instrumentation did not pass')
PY
