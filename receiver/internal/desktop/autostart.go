package desktop

// Start with this computer and Install: the parts shared by every system. The file
// contents (LaunchAgent plist, .desktop entries, the Windows Run value) are built here from
// explicit inputs so they can be tested anywhere; the per-OS files decide paths and write them.
//
// Public API (per-OS implementations):
//
//	SetStartAtLogin(enabled bool) error  — Windows Run key, macOS LaunchAgent, Linux autostart entry
//	StartAtLogin() bool
//	InstallSelf() (path string, err error) — copy to a stable place + Start menu / Applications entry
//	Installed() bool
//	InstallSupported() bool              — false when this copy can't be installed (e.g. not an .app)

import (
	"encoding/binary"
	"errors"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"strings"
)

const (
	// BackgroundFlag is the command-line flag the login entry starts the receiver with.
	BackgroundFlag = "--background"
	// exeName is the program's file name (without .exe).
	exeName = "GallerySlim-Receiver"
	// linuxID names the Linux binary and .desktop files.
	linuxID = "gallery-slim-receiver"
	// launchAgentLabel is the macOS LaunchAgent label (and plist name).
	launchAgentLabel = "com.galleryslim.receiver"
	// appBundle is the macOS application bundle name.
	appBundle = "GallerySlim Receiver.app"
	// runValueName is the Windows Run key value name.
	runValueName = "GallerySlim Receiver"
)

// ErrBadPath is returned for program paths that can't be written into a login entry safely.
var ErrBadPath = errors.New("the program's path contains characters that can't be used")

func hasControl(s string) bool {
	for _, r := range s {
		if r < 0x20 || r == 0x7f {
			return true
		}
	}
	return false
}

// windowsRunValue is the Run key command: "<exe>" --background. Windows paths can't contain
// double quotes, so quoting the path is enough.
func windowsRunValue(exe string) (string, error) {
	if exe == "" || strings.ContainsRune(exe, '"') || hasControl(exe) {
		return "", ErrBadPath
	}
	return `"` + exe + `" ` + BackgroundFlag, nil
}

// xmlEscape escapes text for a plist <string>.
func xmlEscape(s string) string {
	return strings.NewReplacer("&", "&amp;", "<", "&lt;", ">", "&gt;", `"`, "&quot;", "'", "&apos;").Replace(s)
}

// launchAgentPlist is the macOS LaunchAgent that starts the receiver in the background at
// login. KeepAlive is off: quitting from the page means quit.
func launchAgentPlist(exe string) (string, error) {
	if exe == "" || hasControl(exe) {
		return "", ErrBadPath
	}
	return `<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>Label</key><string>` + launchAgentLabel + `</string>
  <key>ProgramArguments</key>
  <array>
    <string>` + xmlEscape(exe) + `</string>
    <string>` + BackgroundFlag + `</string>
  </array>
  <key>RunAtLoad</key><true/>
  <key>KeepAlive</key><false/>
  <key>ProcessType</key><string>Interactive</string>
  <key>LimitLoadToSessionType</key><string>Aqua</string>
</dict>
</plist>
`, nil
}

// desktopExecArg quotes one argument for the Exec key of a .desktop file (Desktop Entry
// Specification): always double-quoted, with ", `, $ and \ escaped by a backslash, "%" doubled;
// then every backslash doubled again because the value itself is an escaped string.
func desktopExecArg(s string) (string, error) {
	if s == "" || hasControl(s) {
		return "", ErrBadPath
	}
	var b strings.Builder
	b.WriteByte('"')
	for _, r := range s {
		switch r {
		case '"', '`', '$', '\\':
			b.WriteByte('\\')
			b.WriteRune(r)
		case '%':
			b.WriteString("%%")
		default:
			b.WriteRune(r)
		}
	}
	b.WriteByte('"')
	return strings.ReplaceAll(b.String(), `\`, `\\`), nil
}

// desktopEntry is a Linux .desktop file starting exe; background adds --background and the
// autostart keys.
func desktopEntry(exe string, background bool) (string, error) {
	q, err := desktopExecArg(exe)
	if err != nil {
		return "", err
	}
	var b strings.Builder
	b.WriteString("[Desktop Entry]\nType=Application\nVersion=1.0\nName=GallerySlim Receiver\n")
	b.WriteString("Comment=Receive photos from the GallerySlim phone app over Wi-Fi\n")
	if background {
		b.WriteString("Exec=" + q + " " + BackgroundFlag + "\n")
		b.WriteString("NoDisplay=true\nX-GNOME-Autostart-enabled=true\n")
	} else {
		b.WriteString("Exec=" + q + "\n")
		b.WriteString("Categories=Graphics;Photography;Network;\n")
	}
	b.WriteString("Terminal=false\n")
	return b.String(), nil
}

// autostartEnabled reads a .desktop autostart file: present and not hidden or disabled.
func autostartEnabled(content string) bool {
	for _, line := range strings.Split(content, "\n") {
		line = strings.TrimSpace(line)
		if strings.EqualFold(line, "Hidden=true") || strings.EqualFold(line, "X-GNOME-Autostart-enabled=false") {
			return false
		}
	}
	return strings.Contains(content, "[Desktop Entry]")
}

// writeFileAtomic writes data to path through a temporary file in the same folder.
func writeFileAtomic(path string, data []byte, mode os.FileMode) error {
	if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
		return err
	}
	tmp, err := os.CreateTemp(filepath.Dir(path), ".gs-*.tmp")
	if err != nil {
		return err
	}
	_, werr := tmp.Write(data)
	if werr == nil {
		werr = tmp.Chmod(mode)
	}
	cerr := tmp.Close()
	if werr != nil || cerr != nil {
		os.Remove(tmp.Name())
		return errors.Join(werr, cerr)
	}
	if err := os.Rename(tmp.Name(), path); err != nil {
		os.Remove(tmp.Name())
		return err
	}
	return nil
}

// copyFileAtomic copies src to dst (replacing it) with the given mode.
func copyFileAtomic(src, dst string, mode os.FileMode) error {
	in, err := os.Open(src)
	if err != nil {
		return err
	}
	defer in.Close()
	if err := os.MkdirAll(filepath.Dir(dst), 0o755); err != nil {
		return err
	}
	tmp, err := os.CreateTemp(filepath.Dir(dst), ".gs-*.tmp")
	if err != nil {
		return err
	}
	_, werr := io.Copy(tmp, in)
	if werr == nil {
		werr = tmp.Chmod(mode)
	}
	if werr == nil {
		werr = tmp.Sync()
	}
	cerr := tmp.Close()
	if werr != nil || cerr != nil {
		os.Remove(tmp.Name())
		return errors.Join(werr, cerr)
	}
	if err := os.Rename(tmp.Name(), dst); err != nil {
		os.Remove(tmp.Name())
		return fmt.Errorf("could not replace %s (is the installed receiver running? quit it first): %w", filepath.Base(dst), err)
	}
	return nil
}

// samePathOS compares two paths after cleaning (case-insensitively where the OS is).
func samePathOS(a, b string, fold bool) bool {
	a, b = filepath.Clean(a), filepath.Clean(b)
	if fold {
		return strings.EqualFold(a, b)
	}
	return a == b
}

// currentExe is os.Executable with symlinks resolved.
func currentExe() (string, error) {
	exe, err := os.Executable()
	if err != nil {
		return "", err
	}
	if r, err := filepath.EvalSymlinks(exe); err == nil {
		exe = r
	}
	return exe, nil
}

// macBundleRoot returns the .app folder that contains exe (…/X.app/Contents/MacOS/exe).
func macBundleRoot(exe string) (string, bool) {
	macos := filepath.Dir(exe)
	contents := filepath.Dir(macos)
	app := filepath.Dir(contents)
	if filepath.Base(macos) == "MacOS" && filepath.Base(contents) == "Contents" && strings.HasSuffix(app, ".app") {
		return app, true
	}
	return "", false
}

// icoEntry finds the image of an .ico file closest to size (preferring larger) and returns
// its bytes (BMP DIB or PNG), as CreateIconFromResourceEx expects them.
func icoEntry(ico []byte, size int) ([]byte, error) {
	if len(ico) < 6 || binary.LittleEndian.Uint16(ico[0:]) != 0 || binary.LittleEndian.Uint16(ico[2:]) != 1 {
		return nil, errors.New("not an icon file")
	}
	n := int(binary.LittleEndian.Uint16(ico[4:]))
	best, bestDiff := -1, 1<<30
	var data []byte
	for i := 0; i < n; i++ {
		e := 6 + 16*i
		if e+16 > len(ico) {
			return nil, errors.New("truncated icon directory")
		}
		w := int(ico[e])
		if w == 0 {
			w = 256
		}
		ln := int(binary.LittleEndian.Uint32(ico[e+8:]))
		off := int(binary.LittleEndian.Uint32(ico[e+12:]))
		if off < 6+16*n || ln <= 0 || off+ln > len(ico) {
			return nil, errors.New("bad icon entry")
		}
		diff := w - size
		if diff < 0 {
			diff = -diff * 2 // a smaller image scaled up looks worse
		}
		if diff < bestDiff {
			best, bestDiff, data = w, diff, ico[off:off+ln]
		}
	}
	if best < 0 {
		return nil, errors.New("empty icon file")
	}
	return data, nil
}
