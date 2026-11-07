#!/bin/sh
# Invoked as a single emulator-runner script line so state (rc, pids)
# stays in one shell. A failing adb/logcat teardown must not mask gradle.
set +e
adb wait-for-device
adb logcat -c || true
adb logcat -v threadtime > "$GITHUB_WORKSPACE/android/logcat-directtest.txt" 2>&1 &
LOGCAT_PID=$!
gradle connectedDebugAndroidTest --no-daemon --stacktrace \
  -Pandroid.testInstrumentationRunnerArguments.class=com.razban.app.DirectTcpIntegrationTest,com.razban.app.DiagnosticUploadTest,com.razban.app.DirectUiFlowTest
rc=$?
kill "$LOGCAT_PID" >/dev/null 2>&1 || true
wait "$LOGCAT_PID" >/dev/null 2>&1 || true
mkdir -p "$GITHUB_WORKSPACE/android/ui-screenshots" 2>/dev/null || true
for pkg in com.razban.app.debug com.razban.app.directtest; do
  adb pull "/sdcard/Android/data/$pkg/files/ui-screenshots" "$GITHUB_WORKSPACE/android/ui-screenshots/$pkg" 2>/dev/null || true
  adb pull "/sdcard/Android/data/$pkg/files/e2e-report.txt" "$GITHUB_WORKSPACE/android/ui-screenshots/" 2>/dev/null || true
done
echo "===== e2e report (redacted) ====="
find "$GITHUB_WORKSPACE/android/ui-screenshots" -name 'e2e-report.txt' -exec cat {} \; 2>/dev/null | sed -E 's/[A-Fa-f0-9]{32,}/[hex]/g' || true
echo "gradle rc=$rc"
exit "$rc"
