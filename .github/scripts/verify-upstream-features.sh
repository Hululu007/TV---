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
IFS=',' read -r -a suites <<< "$classes"
for suite in "${suites[@]}"; do
    [[ "$suite" =~ ^[a-zA-Z_][a-zA-Z0-9_.]*$ ]] || exit 1
    adb logcat -c
    # A crash in one feature must not prevent the other features from producing evidence.
    # The final summary still fails the job unless every instrumentation invocation passes.
    if adb shell am instrument -w -r -e class "$suite" "$test_pkg/androidx.test.runner.AndroidJUnitRunner" | tee "$out/$suite.txt"; then
        printf '0' > "$out/$suite.status"
    else
        printf '1' > "$out/$suite.status"
    fi
    adb logcat -b all -d -v threadtime > "$out/$suite-logcat.txt" 2>&1 || true
    adb exec-out screencap -p > "$out/$suite-screen.png" 2>/dev/null || true
done
python3 - "$out" "$classes" <<'PY'
from pathlib import Path
import json, os, re, sys
root = Path(sys.argv[1])
results = []
output = []
for name in sys.argv[2].split(','):
    content = (root / (name + '.txt')).read_text()
    output.extend([name, content])
    match = re.search(r'^OK \(([1-9][0-9]*) tests?\)', content, re.M)
    passed = match is not None and (root / (name + '.status')).read_text() == '0'
    results.append({'class': name, 'passed': passed, 'tests': int(match[1]) if match else None})
passed = all(item['passed'] for item in results)
summary = {'headSha': os.environ.get('GITHUB_SHA'), 'passed': passed, 'suites': results,
           'passedTests': sum(item['tests'] or 0 for item in results if item['passed'])}
(root / 'suite-summary.json').write_text(json.dumps(summary, indent=2) + '\n')
(root / 'instrumentation.txt').write_text('\n'.join(output))
print(json.dumps(summary, indent=2))
if not passed:
    raise SystemExit('One or more upstream feature instrumentation suites failed')
PY
