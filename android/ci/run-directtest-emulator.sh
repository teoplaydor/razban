#!/bin/sh
# Invoked as a single emulator-runner script line so state (rc, pids)
# stays in one shell. A failing adb/logcat teardown must not mask gradle.
set +e
adb wait-for-device
adb logcat -c || true
adb logcat -v threadtime > "$GITHUB_WORKSPACE/android/logcat-directtest.txt" 2>&1 &
LOGCAT_PID=$!
gradle connectedDebugAndroidTest --no-daemon --stacktrace \
  -Pandroid.testInstrumentationRunnerArguments.class=com.razban.app.DirectTcpIntegrationTest,com.razban.app.DiagnosticUploadTest
rc=$?
kill "$LOGCAT_PID" >/dev/null 2>&1 || true
wait "$LOGCAT_PID" >/dev/null 2>&1 || true
echo "gradle rc=$rc"
exit "$rc"
