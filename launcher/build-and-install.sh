#!/usr/bin/env bash
# Build the launcher APK and install it on the TV as the default home screen.
#
# Talks to the TV with the adb binary when one is on PATH (Android platform-tools, or Homebrew's
# android-platform-tools); otherwise with the pure-Python client in ../tools, whose venv it sets up
# on first use (python3 -m venv ../tools/venv; pip install -r ../tools/requirements.txt).
set -euo pipefail
cd "$(dirname "$0")"
: "${TV_HOST:?set TV_HOST to the TV IP address, e.g. TV_HOST=192.168.1.x $0}"
TV_PORT="${TV_PORT:-5555}"
export JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home -v 17 2>/dev/null || echo /opt/homebrew/opt/openjdk@17)}"
export ANDROID_HOME="${ANDROID_HOME:-$([ -d ~/Library/Android/sdk ] && echo ~/Library/Android/sdk || echo /opt/homebrew/share/android-commandlinetools)}"
export PATH="$JAVA_HOME/bin:$PATH"
[ -x "$JAVA_HOME/bin/java" ] || { echo "No JDK 17 at $JAVA_HOME (brew install openjdk@17, or set JAVA_HOME)"; exit 1; }
[ -f local.properties ] || echo "sdk.dir=$ANDROID_HOME" > local.properties
./gradlew assembleRelease -q --console=plain
APK=app/build/outputs/apk/release/app-release.apk
export MSYS_NO_PATHCONV=1

# ---- how to reach the TV: tv_shell "<cmd>", tv_batch (one command per stdin line), tv_install <apk> ----
ADB="${ADB:-$(command -v adb || true)}"
if [ -n "$ADB" ]; then
  SERIAL="$TV_HOST:$TV_PORT"
  "$ADB" connect "$SERIAL" >/dev/null
  "$ADB" -s "$SERIAL" wait-for-device
  tv_shell()   { "$ADB" -s "$SERIAL" shell "$1"; }
  tv_batch()   { while IFS= read -r line; do [ -n "$line" ] && "$ADB" -s "$SERIAL" shell "$line" < /dev/null; done; }
  tv_install() { "$ADB" -s "$SERIAL" install -r "$1"; }
else
  VENV=../tools/venv
  PY=$VENV/bin/python
  [ -x "$PY" ] || PY=$VENV/Scripts/python.exe
  if [ ! -x "$PY" ]; then
    echo "No adb on PATH: setting up the Python ADB client in $VENV (one time)"
    python3 -m venv "$VENV"
    PY=$VENV/bin/python
    [ -x "$PY" ] || PY=$VENV/Scripts/python.exe
    "$PY" -m pip install -q -r ../tools/requirements.txt
  fi
  tv_shell()   { "$PY" -I ../tools/tv.py "$1"; }
  tv_batch()   { "$PY" -I ../tools/tv.py --batch; }
  tv_install() {
    "$PY" -I ../tools/b64push.py "$1" /data/local/tmp/tvhome.apk
    printf '%s\n' "pm install -r /data/local/tmp/tvhome.apk" "rm -f /data/local/tmp/tvhome.apk" | tv_batch
  }
fi

# Which app owns Home differs by build (com.google.android.tvlauncher on Android TV,
# com.google.android.apps.tv.launcherx on newer Google TV), so ask the TV instead of guessing.
# Ignore an answer of our own launcher, left over from a previous run of this script.
if [ -z "${STOCK_LAUNCHER:-}" ]; then
  STOCK_LAUNCHER=$(tv_shell 'cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME' \
    | tr -d '\r' | tail -n 1 | cut -d/ -f1 || true)
  case "$STOCK_LAUNCHER" in
    com.gathin.tvhome | *[!a-zA-Z0-9._]* | "")   # ours already: whichever stock home is installed (disabled or not)
      STOCK_LAUNCHER=$(tv_shell 'pm list packages' | tr -d '\r' | sed 's/^package://' \
        | grep -xE 'com\.google\.android\.(apps\.tv\.launcherx|tvlauncher)' | head -n 1 || true)
      STOCK_LAUNCHER=${STOCK_LAUNCHER:-com.google.android.tvlauncher} ;;
  esac
  echo "Stock launcher: $STOCK_LAUNCHER"
fi
tv_install "$APK"
printf '%s\n' \
  "pm grant com.gathin.tvhome android.permission.ACCESS_FINE_LOCATION 2>/dev/null; echo location-granted" \
  "cmd package compile -m speed -f com.gathin.tvhome" \
  "cmd package set-home-activity --user 0 com.gathin.tvhome/.MainActivity" \
  "pm disable-user --user 0 $STOCK_LAUNCHER" \
  | tv_batch

# Optional accessibility services. Off unless asked for, and Android switches them off again on
# every reinstall, so pass the flag each time you run this script.
#   INPUT_KEY_SERVICE=1   the remote's Input button (KEYCODE_TV) opens the input switcher from any
#                         app; Google TV needs this once the stock launcher is gone (InputKeyService)
#   HOME_KEY_SERVICE=1    remotes whose Home button sends KEYCODE_ALL_APPS instead of HOME, e.g. Xiaomi
#                         sticks: the key is routed to the launcher (HomeKeyService)
enable_service() {   # appends to enabled_accessibility_services without dropping other services
  svc=com.gathin.tvhome/.$1
  tv_shell "s=\$(settings get secure enabled_accessibility_services); case \"\$s\" in *$1*) ;; null|'') settings put secure enabled_accessibility_services $svc ;; *) settings put secure enabled_accessibility_services \"\$s:$svc\" ;; esac; settings put secure accessibility_enabled 1; echo $1 enabled"
}
if [ "${INPUT_KEY_SERVICE:-0}" = 1 ]; then enable_service InputKeyService; fi
if [ "${HOME_KEY_SERVICE:-0}" = 1 ]; then enable_service HomeKeyService; fi
echo "Installed. Press Home on the remote to see it."
