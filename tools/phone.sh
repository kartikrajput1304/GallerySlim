#!/usr/bin/env bash
# Live device loop for GallerySlim over wireless debugging (Android 11+).
#
#   tools/phone.sh pair <ip:pair-port> <6-digit-code>   one-time pairing
#   tools/phone.sh connect <ip:port>                     after each phone reboot / Wi-Fi change
#   tools/phone.sh install                               build-free install of the current debug APK
#   tools/phone.sh logs                                  stream app logs + crashes to tools/logs/live.log
#   tools/phone.sh pull                                  copy the app's own diagnostic log file
set -euo pipefail

ADB="${ADB:-$HOME/Android/Sdk/platform-tools/adb}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
APK="$ROOT/app/build/outputs/apk/debug/app-debug.apk"
PKG="com.galleryslim"
LOGDIR="$ROOT/tools/logs"
mkdir -p "$LOGDIR"

usage() { sed -n '2,9p' "$0"; exit 1; }

valid_hostport() { [[ "$1" =~ ^[0-9]{1,3}(\.[0-9]{1,3}){3}:[0-9]{2,5}$ ]]; }

case "${1:-}" in
  pair)
    valid_hostport "${2:-}" || { echo "expected ip:port, got '${2:-}'"; exit 1; }
    [[ "${3:-}" =~ ^[0-9]{6}$ ]] || { echo "expected a 6-digit pairing code"; exit 1; }
    "$ADB" pair "$2" "$3"
    ;;
  connect)
    valid_hostport "${2:-}" || { echo "expected ip:port, got '${2:-}'"; exit 1; }
    "$ADB" connect "$2"
    "$ADB" devices -l
    ;;
  install)
    [[ -f "$APK" ]] || { echo "no APK at $APK — build first"; exit 1; }
    "$ADB" install -r "$APK"
    ;;
  logs)
    out="$LOGDIR/live.log"
    "$ADB" logcat -c
    echo "streaming to $out (Ctrl+C to stop)"
    # App tag at all levels, crashes from the runtime, codec/transformer errors, everything else silenced.
    "$ADB" logcat -v time GallerySlim:V AndroidRuntime:E MediaCodec:W CCodec:W Transformer:V \
        ExportException:V WM-WorkerWrapper:W '*:S' | tee "$out"
    ;;
  pull)
    out="$LOGDIR/device-$(date +%Y%m%d-%H%M%S).log"
    "$ADB" exec-out run-as "$PKG" cat files/logs/galleryslim.log > "$out"
    echo "saved $out ($(wc -l < "$out") lines)"
    ;;
  *) usage ;;
esac
