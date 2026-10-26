#!/usr/bin/env bash
# Run after the preview APK and instrumentation APK are installed by verify-tv-ui.sh.
set -euo pipefail
out=artifacts/playback-specs
mkdir -p "$out"
pkg=com.fongmi.android.tv.preview
test_pkg=com.fongmi.android.tv.preview.test
collect() {
    adb pull "/sdcard/Android/data/$pkg/files/native-usability-evidence" "$out/" >/dev/null 2>&1 || true
    adb logcat -b all -d -v threadtime > "$out/logcat.txt" 2>&1 || true
}
trap collect EXIT
classes=com.fongmi.android.tv.ui.PlaybackSpecsCapsuleTest,com.fongmi.android.tv.ui.custom.PlaybackSpecsBackdropTest,com.fongmi.android.tv.ui.custom.PlaybackSpecsControllerTest,com.fongmi.android.tv.bean.VodContentRatingParcelTest
adb logcat -c
adb shell am instrument -w -r -e native_ui_evidence true -e class "$classes" \
    "$test_pkg/androidx.test.runner.AndroidJUnitRunner" | tee "$out/instrumentation.txt"
python3 - "$out" <<'PY'
from pathlib import Path
import json, os, re, sys
root = Path(sys.argv[1])
output = (root / 'instrumentation.txt').read_text()
match = re.search(r'^OK \(([1-9][0-9]*) tests?\)', output, re.M)
summary = {'headSha': os.environ.get('GITHUB_SHA'), 'passed': bool(match),
           'passedTests': int(match[1]) if match else 0}
(root / 'summary.json').write_text(json.dumps(summary, indent=2) + '\n')
print(json.dumps(summary, indent=2))
if not match:
    raise SystemExit('Playback specs instrumentation failed')
PY
