// Package desktop holds the small per-OS desktop helpers the receiver needs: the
// system folder dialog, the suggested save folder, cloud-sync detection, opening
// the browser or a folder, keeping the computer awake, and the config folder.
//
// Everything here is best effort and never runs user-provided text through a
// shell: helper programs get their arguments as separate exec arguments, and
// Windows uses Win32/COM directly through the standard syscall package.
//
// This file holds the parts shared by every system, including the pure helpers
// (cloud detection, default-folder choice, AppleScript quoting, GUID parsing)
// that take explicit inputs so they can be tested on any machine.
package desktop

import (
	"errors"
	"fmt"
	"net/url"
	"os"
	"path"
	"path/filepath"
	"runtime"
	"strconv"
	"strings"
	"sync"
)

// ErrUnsupported is returned when this system has no way to do the operation,
// for example no native folder dialog.
var ErrUnsupported = errors.New("not supported on this system")

const (
	// appName names the config folder.
	appName = "GallerySlim Receiver"
	// altFolderName is suggested inside the home folder when Pictures syncs to the cloud.
	altFolderName = "GallerySlim Photos"
	// defaultTitle is used when PickFolder gets an empty title.
	defaultTitle = "Choose where to save photos"
)

// ConfigDir returns the per-user config folder for "GallerySlim Receiver",
// creating it with mode 0700 (and tightening it to 0700 on Unix if it already
// existed with wider permissions). It is os.UserConfigDir()/GallerySlim Receiver:
// %AppData% on Windows, ~/Library/Application Support on macOS, and
// $XDG_CONFIG_HOME or ~/.config on Linux.
func ConfigDir() (string, error) {
	base, err := os.UserConfigDir()
	if err != nil {
		return "", err
	}
	dir := filepath.Join(base, appName)
	if err := os.MkdirAll(dir, 0o700); err != nil {
		return "", err
	}
	if runtime.GOOS != "windows" {
		if fi, err := os.Stat(dir); err == nil && fi.Mode().Perm()&0o077 != 0 {
			if err := os.Chmod(dir, 0o700); err != nil {
				return "", err
			}
		}
	}
	return dir, nil
}

// noRelease is the release function handed out when keeping awake isn't possible.
func noRelease() {}

// ---------------------------------------------------------------------------
// Input checks shared by OpenURL and OpenFolder.

// checkURL accepts only absolute http and https links, so OpenURL can never be
// used to launch a program or open a local file.
func checkURL(raw string) (string, error) {
	u, err := url.Parse(raw)
	if err != nil {
		return "", fmt.Errorf("not a valid link: %w", err)
	}
	if u.Scheme != "http" && u.Scheme != "https" {
		return "", errors.New("only http and https links can be opened")
	}
	if u.Host == "" {
		return "", errors.New("link has no host")
	}
	return u.String(), nil
}

// checkFolder accepts only an absolute path to an existing folder, so
// OpenFolder can never be used to run a program or open a document.
func checkFolder(p string) (string, error) {
	if p == "" || strings.ContainsRune(p, 0) {
		return "", errors.New("no folder given")
	}
	if !filepath.IsAbs(p) {
		return "", fmt.Errorf("folder path must be absolute: %q", p)
	}
	p = filepath.Clean(p)
	fi, err := os.Stat(p)
	if err != nil {
		return "", err
	}
	if !fi.IsDir() {
		return "", fmt.Errorf("not a folder: %s", p)
	}
	return p, nil
}

// ---------------------------------------------------------------------------
// Cloud-sync detection (pure; per-OS files supply the real env and home).

// osStyle selects the path rules of one family of systems.
type osStyle int

const (
	styleUnix    osStyle = iota // Linux and other Unix: '/' paths, case-sensitive prefixes
	styleDarwin                 // macOS: '/' paths, case-insensitive (default APFS)
	styleWindows                // Windows: '\' or '/' paths, drive letters, case-insensitive
)

func (s osStyle) foldCase() bool { return s != styleUnix }

func (s osStyle) sep() string {
	if s == styleWindows {
		return `\`
	}
	return "/"
}

// splitPath cleans p for the given style and returns its non-empty components.
// It does not touch the file system.
func splitPath(style osStyle, p string) []string {
	if style == styleWindows {
		// Long-path forms: \\?\C:\x → C:\x and \\?\UNC\srv\share → \\srv\share.
		switch {
		case len(p) >= 8 && strings.EqualFold(p[:8], `\\?\UNC\`):
			p = `\\` + p[8:]
		case strings.HasPrefix(p, `\\?\`):
			p = p[4:]
		}
		p = strings.ReplaceAll(p, `\`, "/")
	}
	p = path.Clean(p)
	var parts []string
	for _, c := range strings.Split(p, "/") {
		if c != "" {
			parts = append(parts, c)
		}
	}
	return parts
}

// hasPrefixParts reports whether prefix's components are the leading
// components of parts (so "D:\Sync" is a prefix of "D:\Sync\x" but not of
// "D:\SyncOther"). It returns the remaining components.
func hasPrefixParts(parts, prefix []string, fold bool) ([]string, bool) {
	if len(prefix) == 0 || len(prefix) > len(parts) {
		return nil, false
	}
	for i, c := range prefix {
		if fold {
			if !strings.EqualFold(parts[i], c) {
				return nil, false
			}
		} else if parts[i] != c {
			return nil, false
		}
	}
	return parts[len(prefix):], true
}

// underDir reports whether p is dir or inside it, returning the components below dir.
func underDir(style osStyle, parts []string, dir string, sub ...string) ([]string, bool) {
	if dir == "" {
		return nil, false
	}
	prefix := append(splitPath(style, dir), sub...)
	return hasPrefixParts(parts, prefix, style.foldCase())
}

func hasPrefixFold(s, prefix string) bool {
	return len(s) >= len(prefix) && strings.EqualFold(s[:len(prefix)], prefix)
}

// componentProvider names the sync service a single folder name belongs to, if any.
// Names are compared case-insensitively on every system.
func componentProvider(c string) string {
	switch {
	case strings.EqualFold(c, "OneDrive"), hasPrefixFold(c, "OneDrive - "): // "OneDrive - Contoso" (work/school)
		return "OneDrive"
	case strings.EqualFold(c, "Google Drive"), strings.EqualFold(c, "My Drive"):
		return "Google Drive"
	case strings.EqualFold(c, "Dropbox"), hasPrefixFold(c, "Dropbox ("): // "Dropbox (Personal)", "Dropbox (Team)"
		return "Dropbox"
	case strings.EqualFold(c, "iCloudDrive"), strings.EqualFold(c, "iCloud Drive"):
		return "iCloud Drive"
	case strings.EqualFold(c, "iCloud Photos"): // iCloud for Windows: Pictures\iCloud Photos
		return "iCloud Photos"
	}
	return ""
}

// cloudStorageProvider maps a folder name under ~/Library/CloudStorage
// ("OneDrive-Personal", "GoogleDrive-name@example.com", "Dropbox", "Box-Box")
// to a service name. The account part after '-' is never returned.
func cloudStorageProvider(name string) string {
	switch {
	case hasPrefixFold(name, "OneDrive"):
		return "OneDrive"
	case hasPrefixFold(name, "GoogleDrive"):
		return "Google Drive"
	case hasPrefixFold(name, "Dropbox"):
		return "Dropbox"
	}
	if i := strings.IndexByte(name, '-'); i > 0 {
		name = name[:i]
	}
	return name
}

// cloudProvider is the pure core of CloudSynced: it reports whether p lies in a
// folder that a cloud service syncs, using only the given path rules, home
// folder and environment lookup. It does not touch the file system.
func cloudProvider(style osStyle, p, home string, getenv func(string) string) (bool, string) {
	if p == "" {
		return false, ""
	}
	parts := splitPath(style, p)
	switch style {
	case styleWindows:
		// OneDrive sets these to its sync roots, which may have any name or drive.
		for _, k := range []string{"OneDrive", "OneDriveConsumer", "OneDriveCommercial"} {
			if root := getenv(k); root != "" {
				if _, ok := underDir(style, parts, root); ok {
					return true, "OneDrive"
				}
			}
		}
	case styleDarwin:
		if _, ok := underDir(style, parts, home, "Library", "Mobile Documents"); ok {
			return true, "iCloud Drive"
		}
		if rest, ok := underDir(style, parts, home, "Library", "CloudStorage"); ok && len(rest) > 0 {
			return true, cloudStorageProvider(rest[0])
		}
	case styleUnix:
		if _, ok := underDir(style, parts, home, "Insync"); ok {
			return true, "Insync"
		}
		// GNOME Online Accounts mounts: /run/user/1000/gvfs/google-drive:host=...
		for i := 1; i < len(parts); i++ {
			if parts[i-1] != "gvfs" {
				continue
			}
			switch {
			case strings.HasPrefix(parts[i], "google-drive:"):
				return true, "Google Drive"
			case strings.HasPrefix(parts[i], "onedrive:"):
				return true, "OneDrive"
			}
		}
	}
	for _, c := range parts {
		if prov := componentProvider(c); prov != "" {
			return true, prov
		}
	}
	return false, ""
}

// cloudSyncedResolved checks p as given and, if that finds nothing, again after
// resolving symlinks (for example ~/Pictures linked into a Dropbox folder).
func cloudSyncedResolved(style osStyle, p, home string, getenv func(string) string) (bool, string) {
	if p == "" {
		return false, ""
	}
	if abs, err := filepath.Abs(p); err == nil {
		p = abs
	}
	if ok, prov := cloudProvider(style, p, home, getenv); ok {
		return ok, prov
	}
	if real, err := filepath.EvalSymlinks(p); err == nil && real != p {
		return cloudProvider(style, real, home, getenv)
	}
	return false, ""
}

// ---------------------------------------------------------------------------
// Default folder choice (pure).

// joinStyle joins dir and name with the style's separator.
func joinStyle(style osStyle, dir, name string) string {
	sep := style.sep()
	if strings.HasSuffix(dir, sep) || (style == styleWindows && strings.HasSuffix(dir, "/")) {
		return dir + name
	}
	return dir + sep + name
}

// samePath compares two paths by their cleaned components.
func samePath(style osStyle, a, b string) bool {
	pa, pb := splitPath(style, a), splitPath(style, b)
	if len(pa) != len(pb) {
		return false
	}
	_, ok := hasPrefixParts(pa, pb, style.foldCase())
	return ok || len(pa) == 0
}

// chooseDefault is the pure core of DefaultFolder. pictures is the system's
// Pictures folder ("" if unknown), home the user's home folder, and synced the
// cloud check. Photos are suggested in Pictures itself unless it syncs to the
// cloud; then <home>/GallerySlim Photos is suggested and note says why.
func chooseDefault(style osStyle, pictures, home string, synced func(string) (bool, string)) (folder, note string) {
	if home != "" && (pictures == "" || samePath(style, pictures, home)) {
		// Unknown, or xdg-user-dir fell back to $HOME: never suggest home itself.
		pictures = joinStyle(style, home, "Pictures")
	}
	if pictures == "" {
		return "", ""
	}
	ok, prov := synced(pictures)
	if !ok {
		// A folder of its own inside Pictures, so month folders don't mix with the user's other pictures.
		return joinStyle(style, pictures, altFolderName), ""
	}
	why := fmt.Sprintf("Your Pictures folder syncs with %s, which can move photos to the cloud "+
		"and leave only placeholders on this computer.", prov)
	if home != "" {
		alt := joinStyle(style, home, altFolderName)
		if altSynced, _ := synced(alt); !altSynced {
			return alt, why + " Suggesting " + alt + " instead, which stays on this computer. " +
				"You can still choose any folder."
		}
	}
	return pictures, why + " Choose a folder that doesn't sync if you can."
}

// ---------------------------------------------------------------------------
// AppleScript (pure; used by the macOS folder dialog).

// appleScriptString returns s as an AppleScript string literal. Backslashes and
// double quotes are escaped, newlines/returns/tabs become \n \r \t, and other
// control characters are dropped, so the result can't end the literal early or
// break the line it sits on.
func appleScriptString(s string) string {
	var b strings.Builder
	b.Grow(len(s) + 2)
	b.WriteByte('"')
	for _, r := range s {
		switch r {
		case '\\':
			b.WriteString(`\\`)
		case '"':
			b.WriteString(`\"`)
		case '\n':
			b.WriteString(`\n`)
		case '\r':
			b.WriteString(`\r`)
		case '\t':
			b.WriteString(`\t`)
		default:
			if r < 0x20 || r == 0x7f {
				continue
			}
			b.WriteRune(r)
		}
	}
	b.WriteByte('"')
	return b.String()
}

// appleFolderScript returns the lines of an AppleScript that shows the folder
// dialog and returns the chosen folder's POSIX path, or "" if the user
// cancelled (error -128). Each line is passed to osascript as its own -e.
func appleFolderScript(title, start string) []string {
	prompt := appleScriptString(title)
	lines := []string{"set startLoc to missing value"}
	if start != "" {
		lines = append(lines,
			"try",
			"set startLoc to (POSIX file "+appleScriptString(start)+") as alias",
			"end try")
	}
	return append(lines,
		"try",
		"activate", // bring the dialog in front of the browser; harmless if it fails
		"end try",
		"try",
		"if startLoc is missing value then",
		"set chosen to choose folder with prompt "+prompt,
		"else",
		"set chosen to choose folder with prompt "+prompt+" default location startLoc",
		"end if",
		"on error number -128",
		`return ""`,
		"end try",
		"return POSIX path of chosen")
}

// ---------------------------------------------------------------------------
// GUIDs (pure; used by the Windows code, parsed and checked by the tests on any OS).

// guid has the memory layout of the Win32 GUID structure.
type guid struct {
	Data1 uint32
	Data2 uint16
	Data3 uint16
	Data4 [8]byte
}

// Windows COM and shell identifiers, written as in the Windows SDK headers.
const (
	guidFolderIDPictures    = "{33E28130-4E1E-4676-835A-98395C3BC3BB}" // FOLDERID_Pictures
	guidCLSIDFileOpenDialog = "{DC1C5A9C-E88A-4DDE-A5A1-60F82A20AEF7}" // CLSID_FileOpenDialog
	guidIIDIFileOpenDialog  = "{D57C7288-D4AD-4768-BE02-9D969532D960}" // IID_IFileOpenDialog
	guidIIDIShellItem       = "{43826D1E-E718-42EE-BC55-A1E261C37BFE}" // IID_IShellItem
)

// parseGUID parses "{XXXXXXXX-XXXX-XXXX-XXXX-XXXXXXXXXXXX}" (braces optional).
func parseGUID(s string) (guid, error) {
	var g guid
	s = strings.TrimSuffix(strings.TrimPrefix(s, "{"), "}")
	f := strings.Split(s, "-")
	if len(f) != 5 || len(f[0]) != 8 || len(f[1]) != 4 || len(f[2]) != 4 || len(f[3]) != 4 || len(f[4]) != 12 {
		return g, fmt.Errorf("malformed GUID %q", s)
	}
	d1, err1 := strconv.ParseUint(f[0], 16, 32)
	d2, err2 := strconv.ParseUint(f[1], 16, 16)
	d3, err3 := strconv.ParseUint(f[2], 16, 16)
	if err := errors.Join(err1, err2, err3); err != nil {
		return g, fmt.Errorf("malformed GUID %q: %w", s, err)
	}
	g.Data1, g.Data2, g.Data3 = uint32(d1), uint16(d2), uint16(d3)
	tail := f[3] + f[4]
	for i := range g.Data4 {
		b, err := strconv.ParseUint(tail[2*i:2*i+2], 16, 8)
		if err != nil {
			return g, fmt.Errorf("malformed GUID %q: %w", s, err)
		}
		g.Data4[i] = byte(b)
	}
	return g, nil
}

// mustGUID parses one of the constant GUIDs above.
func mustGUID(s string) guid {
	g, err := parseGUID(s)
	if err != nil {
		panic(err)
	}
	return g
}

// onceRelease wraps f so it runs at most once however many times, and from
// however many goroutines, the result is called.
func onceRelease(f func()) func() {
	if f == nil {
		return noRelease
	}
	return sync.OnceFunc(f)
}
