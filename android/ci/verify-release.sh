#!/usr/bin/env bash
# Verifies the SHIPPED public release APK (signed + R8/proguard minified — which
# the debug-build tests never exercise, so this is where a proguard/JNI breakage
# would surface). The public APK has NO bundled config, so it reproduces the real
# new-user flow: install → redeem the "hub" code → connect → site opens. One bash
# process (the emulator-runner runs each script LINE in its own sh -c).
set +e

PKG="com.razban.app"   # release package — NO .debug suffix
SHOTS="$GITHUB_WORKSPACE/android/relshots"
CDP_EVAL="$GITHUB_WORKSPACE/android/ci/cdp-eval.py"
mkdir -p "$SHOTS"

adb logcat -c || true
adb logcat -v threadtime > "$GITHUB_WORKSPACE/android/rel-logcat.txt" 2>&1 &
LP=$!

echo "::group::install release apk"
APK=$(ls "$GITHUB_WORKSPACE"/release-apk/*.apk 2>/dev/null | head -1)
echo "APK: $APK ($(stat -c%s "$APK" 2>/dev/null) bytes)"
adb install -r "$APK"
echo "::endgroup::"

adb shell appops set "$PKG" ACTIVATE_VPN allow || true
adb shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS || true
adb shell am start -n "$PKG/com.razban.app.ui.WebUiActivity"
sleep 7
adb exec-out screencap -p > "$SHOTS/00-launch.png"

# CDP
SOCK=""
for i in $(seq 1 30); do
  SOCK=$(adb shell cat /proc/net/unix 2>/dev/null | grep -aoE 'webview_devtools_remote_[0-9]+' | head -1)
  [ -n "$SOCK" ] && break
  sleep 1
done
echo "devtools socket: '$SOCK'"
CDP=0
if [ -n "$SOCK" ]; then adb forward tcp:9222 localabstract:"$SOCK" && CDP=1; fi
echo "CDP=$CDP"
ceval() { [ "$CDP" = "1" ] && python3 "$CDP_EVAL" "$1" 2>/dev/null | tail -1; }

# Public APK ships WITHOUT a bundle → redeem the code (real onboarding).
echo "::group::redeem + connect"
REDEEM=$(ceval "new Promise(function(res){var id='r';function h(ev){try{var m=JSON.parse(ev.data);if(m.id===id){window.chrome.webview.removeEventListener('message',h);res(JSON.stringify({ok:!m.error,err:m.error||null}));}}catch(e){}}window.chrome.webview.addEventListener('message',h);window.chrome.webview.postMessage(JSON.stringify({id:id,method:'code.redeem',params:{code:'hub'}}));setTimeout(function(){res('TIMEOUT');},20000);})")
echo "redeem('hub'): $REDEEM"
sleep 4
# Tap the power button to connect.
ceval "(function(){var b=document.querySelector('button[aria-label*=\"тключить\"],button[aria-label*=\"одключить\"]');if(b){b.click();return 'tapped';}return 'NO_BTN';})()"
CONN=0
for i in $(seq 1 35); do
  ST=$(ceval "(document.body.innerText.match(/Подключено|Подключение|Отключено|Ошибка/)||['?'])[0]")
  echo "status[$i]: $ST"
  echo "$ST" | grep -q "Подключено" && { CONN=1; break; }
  sleep 2
done
echo "CONNECTED=$CONN"
echo "::endgroup::"
adb exec-out screencap -p > "$SHOTS/01-connected.png"

# Real site access from the device (traffic goes through the TUN when connected).
echo "::group::site-access (toybox wget through the tunnel)"
for url in https://www.youtube.com/ https://ya.ru/; do
  OUT=$(adb shell "toybox wget -q -O /data/local/tmp/o '$url' >/dev/null 2>&1 && toybox wc -c </data/local/tmp/o 2>/dev/null || echo FAIL")
  echo "wget $url -> bytes=$OUT"
done
echo "::endgroup::"

# The whole point of testing the RELEASE build: catch R8/proguard breakage.
echo "::group::proguard / crash signatures"
grep -aE "UnsatisfiedLinkError|ClassNotFoundException|NoSuchMethodError|NoClassDefFoundError|FATAL EXCEPTION|AndroidRuntime: FATAL|Didn't find class" "$GITHUB_WORKSPACE/android/rel-logcat.txt" | sed -E 's/^[0-9-]+ [0-9:.]+ +[0-9]+ +[0-9]+ //' | head -15 || echo "(none — no proguard/JNI crash)"
echo "razban-core lines:"
grep -aE "razban-core|startTunnel|openTun|service started OK" "$GITHUB_WORKSPACE/android/rel-logcat.txt" | sed -E 's/^[0-9-]+ [0-9:.]+ +[0-9]+ +[0-9]+ //' | head -10 || echo "(none)"
echo "::endgroup::"

kill "$LP" 2>/dev/null || true
echo "shots:"; ls -la "$SHOTS/"
exit 0
