# Android — локальный тестер + сессия 2026-05-31

Маяк для тебя (и future-me): что сделано, как поднять локальный тестер заново,
что дальше. Десктоп-разбор — в `../PROJECT_STUDY.md`.

## 🔴 Главное, что починено (критический баг)

**Android-туннель НИКОГДА не нёс трафик.** Любой dial (даже `outbound/direct`)
падал с `no available network interface` — приложение показывало «подключено»,
но интернета через VPN не было.

**Причина:** `DefaultNetworkMonitor.getInterfaces()` ставил `setFlags(0)` для
всех интерфейсов. sing-box фильтрует доступные сети по `it.Flags & net.FlagUp`
(`route/network.go:291`) → все интерфейсы выглядели «down» → список пустой →
`selectInterfaces()` ничего не возвращал.

**Фикс:** отдаём реальные Linux `IFF_*` флаги (`IFF_UP` всегда + LOOPBACK/
POINTOPOINT/MULTICAST). Туннель теперь видит `eth0`/`wlan0`, трафик идёт.
Проверено в эмуляторе: egress через `hy2-salamander`, реальные up/down байты.

→ Залито: **PR #1 смержен в `main`** (`teoplaydor/razban`, коммит `8a86955`).

## ✅ Что ещё сделано (паритет с десктопом)

- **Live-данные** (`CoreStatus.kt`, новый) — libbox `CommandClient` (Status +
  Connections) через unix `command.sock`. `vpn.stats` (up/down totals + speed),
  per-host/per-process throughput, `apps.connections` с package-именами и
  outbound'ом. Раньше всё было захардкожено в нули. (Подписка на Connections
  ещё и ВКЛЮЧАЕТ учёт трафика на стороне ядра — без подписчика TrafficManager
  не считает.)
- **`WebUiBridge`** — `vpn.stats`/`traffic.throughput`/`apps.connections` отдают
  живые данные из `CoreStatus`.
- **`ConfigStore.adaptForAndroid`** — exact `domain` → `domain_suffix` (инвариант
  #24, чтобы не утекали поддомены: `cdn.discordapp.com` и т.п.).
- **`build-libbox.sh`** — `LIBBOX_TARGETS` для быстрой amd64-only локальной
  сборки (CI по-прежнему собирает 3 ABI).

## 🛠 Как поднять локальный тестер заново

Тулчейн стоит в `C:\Users\Admin\android-toolchain\` (вне репо):
JDK 17, Go 1.24.7, Gradle 8.9, gomobile. SDK — `%LOCALAPPDATA%\Android\Sdk`
(NDK 28, эмулятор, system-image android-34 x86_64, **AEHD**-драйвер ускорения).

**Важно:** ускорение эмулятора работает только при **SVM Mode = Enabled** в
BIOS (ASUS PRIME X470-PRO: Del → F7 → Advanced → CPU Configuration → SVM Mode).
Без него `emulator -accel-check` = код 6, эмулятор не поедет.

```bash
# 1) собрать libbox (только под эмулятор = amd64, ~быстро)
export GOROOT=/c/Users/Admin/android-toolchain/go
export GOPATH=/c/Users/Admin/android-toolchain/gopath
export PATH=$GOROOT/bin:$GOPATH/bin:$PATH
export ANDROID_HOME=/c/Users/Admin/AppData/Local/Android/Sdk
export ANDROID_NDK_HOME=$(ls -d $ANDROID_HOME/ndk/* | sort -V | tail -1)
cd android && LIBBOX_TARGETS=android/amd64 bash build-libbox.sh v1.13.12

# 2) собрать APK (JDK 17 обязателен — системная Java 25 ломает AGP)
#    (PowerShell) JAVA_HOME=...jdk17; gradle-8.9\bin\gradle.bat -p android assembleDebug

# 3) эмулятор (изолированный QEMU-NAT — хост-интернет не трогается)
emulator -avd razban_test -no-audio -no-boot-anim -gpu swiftshader_indirect -memory 4096 -cores 4 &

# 4) поставить + запустить + дать VPN-разрешение без диалога
adb install -r -t android/app/build/outputs/apk/debug/app-debug.apk
adb shell appops set com.razban.app.debug ACTIVATE_VPN allow
adb shell am start -n com.razban.app.debug/com.razban.app.ui.WebUiActivity

# логи ядра / RPC / сети
adb logcat -s razban-core razban-bridge razban-sb razban-net
# трафик через clash_api внутри гостя
adb shell "printf 'GET /connections HTTP/1.0\r\n\r\n' | nc -w 3 127.0.0.1 9090"
```

Изоляция: VPN живёт ВНУТРИ гостевого Android (QEMU usermode NAT). Хост видит
только обычный исходящий трафик процесса эмулятора — маршруты/дефолт-роут хоста
не трогаются, интернет на основном компе отрубиться не может.

Симуляция мобильной сети: `-netspeed lte`/`-netdelay`, либо на лету через
консоль эмулятора (`telnet localhost 5554` → `network speed lte`).

## ⏭ Что дальше (паритет — следующие сессии)

Большие фичи, которые лучше делать с тестом/ревью, не вслепую во сне:
1. **byedpi как `libciadpi.so`** — NDK cross-compile C-кода + JNI start/stop,
   `adaptForAndroid` перестанет стрипать `dpi-bypass`. Даст DPI-fast-path.
2. **Smart per-domain классификация** — порт `BackgroundProber.AutoApplyAsync`
   как корутина (DNS через `LocalResolver`, TCP/TLS через `protect()`-сокеты),
   first-DNS → классифицировать → `reload()`.
3. **QR-импорт** конфигов (CameraX + ML Kit / ZXing).
4. Мелочи из `PROJECT_STUDY.md` §8 (Notification открывает не тот Activity;
   `BootReceiver` заглушка; hardcoded версия APK в CI).

## Публичный APK

Сборка APK на CI гейтится тегом `android-v*` (не push в main). Чтобы выкатить
публичный APK с этим фиксом: `git tag android-v0.1.11 && git push --tags`
(или workflow_dispatch воркфлоу `android`). Я НЕ делал этого — релиз твой.
