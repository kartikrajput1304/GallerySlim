#!/usr/bin/env bash
# Builds GallerySlim Receiver for Windows, macOS and Linux into ../dist/receiver/.
# Standard library only; CGO off; reproducible flags. Unsigned (signing is pending company IT).
set -euo pipefail

VERSION="${VERSION:-0.1.0}"
GO="${GO:-$HOME/tools/go1.27.3/bin/go}"
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OUT="$(cd "$HERE/.." && pwd)/dist/receiver"
NAME="GallerySlim-Receiver"
APP="GallerySlim Receiver.app"

export CGO_ENABLED=0 GOTOOLCHAIN=local GOPROXY=off GOFLAGS=-mod=mod
LDFLAGS="-s -w -X main.version=${VERSION}"

case "$VERSION" in
  *[!0-9A-Za-z.+-]*|"") echo "bad VERSION '$VERSION'" >&2; exit 2 ;;
esac
[ -x "$GO" ] || { echo "Go not found at $GO (set GO=...)" >&2; exit 2; }
command -v zip >/dev/null || { echo "zip is needed for the macOS bundles" >&2; exit 2; }

# Fixed timestamp inside archives, so rebuilding the same source gives the same files.
STAMP="2026-01-01 00:00:00"

case "$OUT" in */dist/receiver) ;; *) echo "unexpected output folder $OUT" >&2; exit 2 ;; esac
rm -rf "$OUT"
mkdir -p "$OUT"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

# Same modes and times for everyone, whatever the builder's umask: folders 755, files 644
# (the executable is set back to 755 after this).
normalise() {
  find "$1" -type d -exec chmod 0755 {} +
  find "$1" -type f -exec chmod 0644 {} +
  find "$1" -exec env TZ=UTC touch -d "$STAMP" {} +
}

build() { # goos goarch output
  echo "building $1/$2"
  (cd "$HERE" && GOOS="$1" GOARCH="$2" "$GO" build -trimpath -ldflags "$LDFLAGS" -o "$3" .)
}

# Windows: a console program (no -H windowsgui), see README "Why a console window".
for arch in amd64 arm64; do
  mkdir -p "$OUT/windows-$arch"
  build windows "$arch" "$OUT/windows-$arch/$NAME.exe"
  chmod 0755 "$OUT/windows-$arch" "$OUT/windows-$arch/$NAME.exe"
done

# macOS: an unsigned .app bundle per architecture, zipped.
plist() {
  cat <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>CFBundleName</key><string>GallerySlim Receiver</string>
  <key>CFBundleDisplayName</key><string>GallerySlim Receiver</string>
  <key>CFBundleIdentifier</key><string>com.galleryslim.receiver</string>
  <key>CFBundleExecutable</key><string>${NAME}</string>
  <key>CFBundlePackageType</key><string>APPL</string>
  <key>CFBundleShortVersionString</key><string>${VERSION}</string>
  <key>CFBundleVersion</key><string>${VERSION}</string>
  <key>CFBundleInfoDictionaryVersion</key><string>6.0</string>
  <key>LSMinimumSystemVersion</key><string>13.0</string>
  <key>LSUIElement</key><true/>
  <key>NSLocalNetworkUsageDescription</key>
  <string>GallerySlim Receiver looks for your phone on your Wi-Fi network and copies your photos from it.</string>
  <key>NSHumanReadableCopyright</key><string>GallerySlim</string>
</dict>
</plist>
EOF
}
for arch in arm64 amd64; do
  dir="$WORK/mac-$arch"
  mkdir -p "$dir/$APP/Contents/MacOS"
  build darwin "$arch" "$dir/$APP/Contents/MacOS/$NAME"
  plist > "$dir/$APP/Contents/Info.plist"
  printf 'APPL????' > "$dir/$APP/Contents/PkgInfo"
  normalise "$dir"
  chmod 0755 "$dir/$APP/Contents/MacOS/$NAME"
  (cd "$dir" && TZ=UTC zip -q -X -r "$OUT/$NAME-macos-$arch.zip" "$APP")
done

# Linux: tar.gz that keeps the executable bit.
for arch in amd64 arm64; do
  dir="$WORK/linux-$arch/$NAME-linux-$arch"
  mkdir -p "$dir"
  build linux "$arch" "$dir/$NAME"
  chmod 0755 "$dir/$NAME"
  cp "$HERE/README.md" "$dir/README.md"
  normalise "$WORK/linux-$arch"
  chmod 0755 "$dir/$NAME"
  tar -C "$WORK/linux-$arch" --sort=name --owner=0 --group=0 --numeric-owner \
      --mtime="$STAMP UTC" -czf "$OUT/$NAME-linux-$arch.tar.gz" "$NAME-linux-$arch"
done

cp "$HERE/README.md" "$OUT/README.md"
chmod 0644 "$OUT/README.md"
(cd "$OUT" && find . -type f ! -name SHA256SUMS | sed 's|^\./||' | LC_ALL=C sort | xargs -d '\n' sha256sum > SHA256SUMS)
echo
echo "Done: $OUT"
(cd "$OUT" && ls -l -R)
