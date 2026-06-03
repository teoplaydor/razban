#!/usr/bin/env bash
# Emulator-side UI tour. Runs INSIDE reactivecircus/android-emulator-runner
# AFTER the emulator booted. CRITICAL: that action executes the workflow
# `script:` LINE-BY-LINE in separate `sh -c` invocations, so shell variables,
# loops, `set` and traps do NOT persist across newlines. Therefore all real
# logic lives HERE, in ONE bash process invoked as a single `bash ci/ui-tour.sh`
# line — where state behaves normally.
#
# It: installs the debug app, pre-grants VPN consent so the 1.2s auto-connect
# goes through silently, screenshots the launch, brings up CDP over the WebView
# devtools socket, waits for the tunnel to connect, then tours every HashRouter
# route — screenshotting each (adb screencap) and dumping innerText (a blank
# dump = a crashed/blank React screen). Never hard-fails (exit 0) so the
# `if: always()` artifact upload always runs.
set +e

PKG="${PKG:-com.razban.app.debug}"
SHOTS="$GITHUB_WORKSPACE/android/shots"
CDP_EVAL="$GITHUB_WORKSPACE/android/ci/cdp-eval.py"
mkdir -p "$SHOTS"

adb logcat -c || true
adb logcat -v threadtime > "$GITHUB_WORKSPACE/android/ui-logcat.txt" 2>&1 &
LP=$!

echo "::group::install"
gradle :app:installDebug --no-daemon --stacktrace
echo "::endgroup::"

# Pre-grant everything the app would otherwise prompt for.
adb shell appops set "$PKG" ACTIVATE_VPN allow || true
adb shell appops set "$PKG" REQUEST_INSTALL_PACKAGES allow || true
adb shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS || true

# Launch the WebView UI activity directly.
adb shell am start -n "$PKG/com.razban.app.ui.WebUiActivity"
sleep 6
adb exec-out screencap -p > "$SHOTS/00-launch.png"

# ── Bring up CDP over the WebView devtools unix socket ──
echo "::group::cdp-setup"
SOCK=""
for i in $(seq 1 30); do
  SOCK=$(adb shell cat /proc/net/unix 2>/dev/null | grep -aoE 'webview_devtools_remote_[0-9]+' | head -1)
  [ -n "$SOCK" ] && break
  sleep 1
done
echo "devtools socket: '$SOCK'"
python3 -c "import websockets; print('websockets', websockets.__version__)" || echo "WEBSOCKETS IMPORT FAILED"
CDP=0
if [ -n "$SOCK" ]; then
  adb forward tcp:9222 localabstract:"$SOCK" && echo "forwarded tcp:9222 -> $SOCK"
  if python3 -c "import urllib.request,json; print('targets:', json.dumps(json.load(urllib.request.urlopen('http://127.0.0.1:9222/json',timeout=8)))[:400])"; then
    CDP=1
  else
    echo "JSON ENDPOINT FAILED"
  fi
else
  echo "NO devtools socket found"
fi
echo "CDP=$CDP"
echo "::endgroup::"

ceval() { [ "$CDP" = "1" ] && python3 "$CDP_EVAL" "$1" 2>/dev/null | tail -1; }

# ── Wait for the tunnel to reach connected (native auto-connect) ──
echo "::group::connect-poll"
CONNECTED=0
for i in $(seq 1 30); do
  ST=$(ceval "(document.body.innerText.match(/Подключено|Подключение|Отключено|Ошибка/)||['(no-cdp)'])[0]")
  echo "ui-status[$i]: $ST"
  echo "$ST" | grep -q "Подключено" && { CONNECTED=1; break; }
  sleep 2
done
echo "CONNECTED=$CONNECTED"
ceval "JSON.stringify({status:(document.body.innerText.match(/Подключено|Подключение|Отключено/)||[''])[0], hasSpeed:/КБ\/с|МБ\/с|Б\/с/.test(document.body.innerText), proto:(document.body.innerText.match(/VLESS|Reality|Hysteria|AnyTLS|ShadowTLS/gi)||[]).length})"
echo "::endgroup::"

adb exec-out screencap -p > "$SHOTS/00b-after-connect.png"

# ── Tour every route: CDP sets the hash, screencap captures the device frame ──
echo "::group::ui-tour"
for pair in home:01 servers:02 stats:03 settings:04 about:05; do
  route="/${pair%%:*}"; n="${pair##*:}"
  echo "--- nav $route ---"
  ceval "location.hash='#$route'; location.hash"
  sleep 3
  adb exec-out screencap -p > "$SHOTS/${n}-${pair%%:*}.png"
  echo "### $route innerText:"
  ceval "((document.querySelector('#root')||document.body).innerText||'(empty)').replace(/\s+/g,' ').slice(0,400)"
done
echo "::endgroup::"

# ── Functional: tap an app chip on the routing board → it must move to the VPN
#    zone (proxyApps pin → injectUserRoutes → hot-reload). Proves the full
#    UI→bridge→native→store→re-render write path, not just rendering. ──
echo "::group::functional-tap-route"
if [ "$CDP" = "1" ]; then
  ceval "location.hash='#/home'; 'home'" >/dev/null; sleep 2
  PH_BEFORE=$(ceval "String(/Перетащите сюда приложение/.test(document.body.innerText))")
  TAP=$(ceval "(function(){var b=Array.from(document.querySelectorAll('button')).find(function(x){return /перетащите в VPN/i.test(x.getAttribute('title')||'');});if(!b)return 'NO_CHIP';var n=(b.textContent||'').trim();b.click();return 'CLICKED:'+n;})()")
  sleep 3
  PH_AFTER=$(ceval "String(/Перетащите сюда приложение/.test(document.body.innerText))")
  echo "tap-route: placeholderBefore=$PH_BEFORE  tap=$TAP  placeholderAfter=$PH_AFTER"
  adb exec-out screencap -p > "$SHOTS/06-after-tap.png"
  if [ "$PH_BEFORE" = "true" ] && [ "$PH_AFTER" = "false" ]; then
    echo "FUNCTIONAL-OK: app routed into the VPN zone"
  else
    echo "FUNCTIONAL-NOTE: VPN-zone state did not change as expected (before=$PH_BEFORE after=$PH_AFTER tap=$TAP)"
  fi
else
  echo "CDP unavailable — skipped functional tap test"
fi
echo "::endgroup::"

kill "$LP" 2>/dev/null || true
echo "shots:"; ls -la "$SHOTS/"
exit 0
