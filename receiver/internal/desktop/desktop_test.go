package desktop

import (
	"errors"
	"strings"
	"sync"
	"testing"
	"unsafe"
)

// envOf returns a getenv function backed by m.
func envOf(m map[string]string) func(string) string {
	return func(k string) string { return m[k] }
}

func TestCloudProvider(t *testing.T) {
	winEnv := envOf(map[string]string{
		"OneDrive":           `C:\Users\Asha\OneDrive`,
		"OneDriveConsumer":   `C:\Users\Asha\OneDrive`,
		"OneDriveCommercial": `C:\Users\Asha\OneDrive - Contoso`,
	})
	movedEnv := envOf(map[string]string{"OneDrive": `D:\Sync`})
	noEnv := envOf(nil)

	tests := []struct {
		name   string
		style  osStyle
		home   string
		env    func(string) string
		path   string
		synced bool
		prov   string
	}{
		// Windows
		{"win OneDrive Pictures", styleWindows, `C:\Users\Asha`, winEnv, `C:\Users\Asha\OneDrive\Pictures`, true, "OneDrive"},
		{"win lower case", styleWindows, `C:\Users\Asha`, winEnv, `c:\users\asha\onedrive\pictures`, true, "OneDrive"},
		{"win forward slashes", styleWindows, `C:\Users\Asha`, winEnv, `C:/Users/Asha/OneDrive/Pictures`, true, "OneDrive"},
		{"win long path form", styleWindows, `C:\Users\Asha`, winEnv, `\\?\C:\Users\Asha\OneDrive\Pictures`, true, "OneDrive"},
		{"win OneDrive business", styleWindows, `C:\Users\Asha`, winEnv, `C:\Users\Asha\OneDrive - Contoso\Pictures`, true, "OneDrive"},
		{"win OneDrive moved (env only)", styleWindows, `C:\Users\Asha`, movedEnv, `D:\Sync\Fotos`, true, "OneDrive"},
		{"win sibling of OneDrive root", styleWindows, `C:\Users\Asha`, movedEnv, `D:\SyncOther\Fotos`, false, ""},
		{"win dotdot out of OneDrive", styleWindows, `C:\Users\Asha`, movedEnv, `D:\Sync\..\Photos`, false, ""},
		{"win plain Pictures", styleWindows, `C:\Users\Asha`, winEnv, `C:\Users\Asha\Pictures`, false, ""},
		{"win Hindi plain", styleWindows, `C:\Users\आशा`, noEnv, `C:\Users\आशा\चित्र`, false, ""},
		{"win Hindi OneDrive", styleWindows, `C:\Users\आशा`, noEnv, `C:\Users\आशा\OneDrive\चित्र`, true, "OneDrive"},
		{"win name only looks similar", styleWindows, `C:\Users\Asha`, noEnv, `D:\Photos\OneDriveBackup`, false, ""},
		{"win Google Drive letter", styleWindows, `C:\Users\Asha`, noEnv, `G:\My Drive\Photos`, true, "Google Drive"},
		{"win Google Drive folder", styleWindows, `C:\Users\Asha`, noEnv, `C:\Users\Asha\Google Drive\Photos`, true, "Google Drive"},
		{"win Dropbox", styleWindows, `C:\Users\Asha`, noEnv, `C:\Users\Asha\Dropbox\Camera Uploads`, true, "Dropbox"},
		{"win Dropbox personal", styleWindows, `C:\Users\Asha`, noEnv, `C:\Users\Asha\Dropbox (Personal)\x`, true, "Dropbox"},
		{"win iCloudDrive", styleWindows, `C:\Users\Asha`, noEnv, `C:\Users\Asha\iCloudDrive\Photos`, true, "iCloud Drive"},
		{"win iCloud Photos", styleWindows, `C:\Users\Asha`, noEnv, `C:\Users\Asha\Pictures\iCloud Photos`, true, "iCloud Photos"},
		{"win other drive", styleWindows, `C:\Users\Asha`, winEnv, `E:\Photos`, false, ""},
		{"win UNC share", styleWindows, `C:\Users\Asha`, winEnv, `\\nas\photos\2024`, false, ""},
		{"win empty", styleWindows, `C:\Users\Asha`, winEnv, ``, false, ""},

		// macOS
		{"mac iCloud Drive", styleDarwin, "/Users/asha", noEnv, "/Users/asha/Library/Mobile Documents/com~apple~CloudDocs/Photos", true, "iCloud Drive"},
		{"mac OneDrive", styleDarwin, "/Users/asha", noEnv, "/Users/asha/Library/CloudStorage/OneDrive-Personal/Pictures", true, "OneDrive"},
		{"mac Google Drive", styleDarwin, "/Users/asha", noEnv, "/Users/asha/Library/CloudStorage/GoogleDrive-asha@example.com/My Drive", true, "Google Drive"},
		{"mac Dropbox", styleDarwin, "/Users/asha", noEnv, "/Users/asha/Library/CloudStorage/Dropbox/Photos", true, "Dropbox"},
		{"mac other provider", styleDarwin, "/Users/asha", noEnv, "/Users/asha/Library/CloudStorage/Box-Box/x", true, "Box"},
		{"mac CloudStorage itself", styleDarwin, "/Users/asha", noEnv, "/Users/asha/Library/CloudStorage", false, ""},
		{"mac case-insensitive", styleDarwin, "/Users/asha", noEnv, "/users/ASHA/library/cloudstorage/OneDrive-Contoso", true, "OneDrive"},
		{"mac legacy Dropbox", styleDarwin, "/Users/asha", noEnv, "/Users/asha/Dropbox/Photos", true, "Dropbox"},
		{"mac Pictures", styleDarwin, "/Users/asha", noEnv, "/Users/asha/Pictures", false, ""},
		{"mac external disk", styleDarwin, "/Users/asha", noEnv, "/Volumes/Ext/Photos", false, ""},
		{"mac other user's library", styleDarwin, "/Users/asha", noEnv, "/Users/ravi/Library/Mobile Documents/x", false, ""},

		// Linux
		{"linux Insync", styleUnix, "/home/asha", noEnv, "/home/asha/Insync/asha@example.com/Google Drive/Photos", true, "Insync"},
		{"linux Dropbox", styleUnix, "/home/asha", noEnv, "/home/asha/Dropbox/Photos", true, "Dropbox"},
		{"linux OneDrive client", styleUnix, "/home/asha", noEnv, "/home/asha/OneDrive/Pictures", true, "OneDrive"},
		{"linux gvfs Google Drive", styleUnix, "/home/asha", noEnv, "/run/user/1000/gvfs/google-drive:host=gmail.com,user=asha/abc", true, "Google Drive"},
		{"linux gvfs OneDrive", styleUnix, "/home/asha", noEnv, "/run/user/1000/gvfs/onedrive:host=x,user=asha/abc", true, "OneDrive"},
		{"linux Pictures", styleUnix, "/home/asha", noEnv, "/home/asha/Pictures", false, ""},
		{"linux similar name", styleUnix, "/home/asha", noEnv, "/home/asha/dropbox-notes", false, ""},
		{"linux Hindi", styleUnix, "/home/asha", noEnv, "/home/asha/चित्र/2024", false, ""},
		{"linux mac-style path is not iCloud", styleUnix, "/home/asha", noEnv, "/home/asha/Library/Mobile Documents/x", false, ""},
	}
	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			synced, prov := cloudProvider(tc.style, tc.path, tc.home, tc.env)
			if synced != tc.synced || prov != tc.prov {
				t.Errorf("cloudProvider(%q) = %v, %q; want %v, %q", tc.path, synced, prov, tc.synced, tc.prov)
			}
		})
	}
}

func TestCloudStorageProviderHidesAccount(t *testing.T) {
	if got := cloudStorageProvider("GoogleDrive-asha@example.com"); got != "Google Drive" {
		t.Fatalf("got %q", got)
	}
	if got := cloudStorageProvider("pCloud-asha@example.com"); strings.Contains(got, "@") {
		t.Fatalf("account leaked into provider name: %q", got)
	}
}

func TestChooseDefault(t *testing.T) {
	syncedFor := func(style osStyle, home string, env map[string]string) func(string) (bool, string) {
		return func(p string) (bool, string) { return cloudProvider(style, p, home, envOf(env)) }
	}
	oneDrive := map[string]string{"OneDrive": `C:\Users\Asha\OneDrive`}

	tests := []struct {
		name      string
		style     osStyle
		pictures  string
		home      string
		env       map[string]string
		want      string
		noteHas   []string // empty: note must be empty
		noteLacks string
	}{
		{"win plain Pictures", styleWindows, `C:\Users\Asha\Pictures`, `C:\Users\Asha`, oneDrive,
			`C:\Users\Asha\Pictures\GallerySlim Photos`, nil, ""},
		{"win Pictures in OneDrive", styleWindows, `C:\Users\Asha\OneDrive\Pictures`, `C:\Users\Asha`, oneDrive,
			`C:\Users\Asha\GallerySlim Photos`, []string{"OneDrive", `C:\Users\Asha\GallerySlim Photos`, "any folder"}, ""},
		{"win Hindi user in OneDrive", styleWindows, `C:\Users\आशा\OneDrive\चित्र`, `C:\Users\आशा`, nil,
			`C:\Users\आशा\GallerySlim Photos`, []string{"OneDrive"}, ""},
		{"win unknown Pictures", styleWindows, "", `C:\Users\Asha`, nil,
			`C:\Users\Asha\Pictures\GallerySlim Photos`, nil, ""},
		{"win whole profile synced", styleWindows, `C:\Users\Asha\Pictures`, `C:\Users\Asha`,
			map[string]string{"OneDrive": `C:\Users\Asha`},
			`C:\Users\Asha\Pictures`, []string{"OneDrive", "doesn't sync"}, "Suggesting"},
		{"mac Pictures", styleDarwin, "/Users/asha/Pictures", "/Users/asha", nil,
			"/Users/asha/Pictures/GallerySlim Photos", nil, ""},
		{"mac Pictures in CloudStorage", styleDarwin, "/Users/asha/Library/CloudStorage/OneDrive-Personal/Pictures", "/Users/asha", nil,
			"/Users/asha/GallerySlim Photos", []string{"OneDrive"}, ""},
		{"linux xdg gave nothing", styleUnix, "", "/home/asha", nil,
			"/home/asha/Pictures/GallerySlim Photos", nil, ""},
		{"linux xdg fell back to home", styleUnix, "/home/asha/", "/home/asha", nil,
			"/home/asha/Pictures/GallerySlim Photos", nil, ""},
		{"linux localized Pictures", styleUnix, "/home/asha/चित्र", "/home/asha", nil,
			"/home/asha/चित्र/GallerySlim Photos", nil, ""},
		{"linux Pictures in Dropbox", styleUnix, "/home/asha/Dropbox/Pictures", "/home/asha", nil,
			"/home/asha/GallerySlim Photos", []string{"Dropbox"}, ""},
		{"no home, no pictures", styleUnix, "", "", nil, "", nil, ""},
	}
	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			got, note := chooseDefault(tc.style, tc.pictures, tc.home, syncedFor(tc.style, tc.home, tc.env))
			if got != tc.want {
				t.Errorf("folder = %q, want %q", got, tc.want)
			}
			if len(tc.noteHas) == 0 && note != "" {
				t.Errorf("unexpected note %q", note)
			}
			for _, s := range tc.noteHas {
				if !strings.Contains(note, s) {
					t.Errorf("note %q lacks %q", note, s)
				}
			}
			if tc.noteLacks != "" && strings.Contains(note, tc.noteLacks) {
				t.Errorf("note %q should not contain %q", note, tc.noteLacks)
			}
		})
	}
}

func TestAppleScriptString(t *testing.T) {
	tests := []struct{ in, want string }{
		{"", `""`},
		{"Where should photos go?", `"Where should photos go?"`},
		{`say "hi"`, `"say \"hi\""`},
		{`C:\new\folder`, `"C:\\new\\folder"`},
		{`\"`, `"\\\""`},
		{"line1\nline2\r\tend", `"line1\nline2\r\tend"`},
		{"bell\x07nul\x00del\x7f", `"bellnuldel"`},
		{"/Users/आशा/चित्र", `"/Users/आशा/चित्र"`},
		// An attempt to end the literal and run a command stays inside it.
		{`" & (do shell script "rm -rf ~") & "`, `"\" & (do shell script \"rm -rf ~\") & \""`},
	}
	for _, tc := range tests {
		if got := appleScriptString(tc.in); got != tc.want {
			t.Errorf("appleScriptString(%q) = %s, want %s", tc.in, got, tc.want)
		}
	}
}

// unquoteAppleScript reverses appleScriptString, failing on anything that
// would end the literal early.
func unquoteAppleScript(t *testing.T, lit string) string {
	t.Helper()
	if len(lit) < 2 || lit[0] != '"' || lit[len(lit)-1] != '"' {
		t.Fatalf("not a literal: %s", lit)
	}
	var b strings.Builder
	body := lit[1 : len(lit)-1]
	for i := 0; i < len(body); i++ {
		switch c := body[i]; c {
		case '"':
			t.Fatalf("unescaped quote at %d in %s", i, lit)
		case '\\':
			i++
			if i == len(body) {
				t.Fatalf("dangling backslash in %s", lit)
			}
			switch body[i] {
			case '\\', '"':
				b.WriteByte(body[i])
			case 'n':
				b.WriteByte('\n')
			case 'r':
				b.WriteByte('\r')
			case 't':
				b.WriteByte('\t')
			default:
				t.Fatalf("unknown escape \\%c in %s", body[i], lit)
			}
		case '\n', '\r':
			t.Fatalf("raw line break in %s", lit)
		default:
			b.WriteByte(c)
		}
	}
	return b.String()
}

func TestAppleScriptStringRoundTrip(t *testing.T) {
	for _, s := range []string{`a"b\c`, "x\ny", `\\\"\\`, "/Users/आशा/My \"Photos\"\\", `"""`, `\`} {
		if got := unquoteAppleScript(t, appleScriptString(s)); got != s {
			t.Errorf("round trip of %q gave %q", s, got)
		}
	}
}

func TestAppleFolderScript(t *testing.T) {
	title := `Pick "a" folder\`
	start := "/Users/आशा/Pictures"
	lines := appleFolderScript(title, start)
	joined := strings.Join(lines, "\n")
	for _, want := range []string{
		"choose folder with prompt " + appleScriptString(title),
		"default location startLoc",
		"(POSIX file " + appleScriptString(start) + ") as alias",
		"on error number -128",
		"return POSIX path of chosen",
	} {
		if !strings.Contains(joined, want) {
			t.Errorf("script lacks %q:\n%s", want, joined)
		}
	}
	for _, l := range lines {
		if strings.ContainsAny(l, "\n\r") {
			t.Errorf("line contains a line break: %q", l)
		}
	}
	if s := strings.Join(appleFolderScript("t", ""), "\n"); strings.Contains(s, "POSIX file") {
		t.Errorf("no start folder, but script sets one:\n%s", s)
	}
}

func TestParseGUID(t *testing.T) {
	g, err := parseGUID(guidFolderIDPictures)
	if err != nil {
		t.Fatal(err)
	}
	want := guid{0x33E28130, 0x4E1E, 0x4676, [8]byte{0x83, 0x5A, 0x98, 0x39, 0x5C, 0x3B, 0xC3, 0xBB}}
	if g != want {
		t.Fatalf("got %+v, want %+v", g, want)
	}
	if unsafe.Sizeof(g) != 16 {
		t.Fatalf("guid is %d bytes, want 16", unsafe.Sizeof(g))
	}
	// In memory (little-endian, as on every Windows CPU) a GUID is stored in
	// this mixed-endian order.
	mem := unsafe.Slice((*byte)(unsafe.Pointer(&g)), 16)
	wantMem := []byte{0x30, 0x81, 0xE2, 0x33, 0x1E, 0x4E, 0x76, 0x46, 0x83, 0x5A, 0x98, 0x39, 0x5C, 0x3B, 0xC3, 0xBB}
	if isLittleEndian() && string(mem) != string(wantMem) {
		t.Fatalf("memory layout % X, want % X", mem, wantMem)
	}
	for _, s := range []string{guidCLSIDFileOpenDialog, guidIIDIFileOpenDialog, guidIIDIShellItem} {
		if _, err := parseGUID(s); err != nil {
			t.Errorf("%s: %v", s, err)
		}
	}
	for _, bad := range []string{"", "{}", "{33E28130-4E1E-4676-835A-98395C3BC3B}", "{33E28130-4E1E-4676-835A-98395C3BC3BX}", "33E281304E1E4676835A98395C3BC3BB"} {
		if _, err := parseGUID(bad); err == nil {
			t.Errorf("parseGUID(%q) accepted a malformed GUID", bad)
		}
	}
}

func isLittleEndian() bool {
	x := uint16(1)
	return *(*byte)(unsafe.Pointer(&x)) == 1
}

func TestCheckURL(t *testing.T) {
	for _, ok := range []string{"http://127.0.0.1:41234/abc/", "https://example.com/x?y=1"} {
		if _, err := checkURL(ok); err != nil {
			t.Errorf("checkURL(%q): %v", ok, err)
		}
	}
	for _, bad := range []string{"", "file:///etc/passwd", `C:\Windows\System32\calc.exe`, "calc.exe", "javascript:alert(1)", "http://", "ms-settings:", "http://a b/\x00"} {
		if _, err := checkURL(bad); err == nil {
			t.Errorf("checkURL(%q) accepted it", bad)
		}
	}
}

func TestCheckFolder(t *testing.T) {
	dir := t.TempDir()
	if got, err := checkFolder(dir); err != nil || got != dir {
		t.Errorf("checkFolder(%q) = %q, %v", dir, got, err)
	}
	for _, bad := range []string{"", "relative/dir", dir + "/missing", "\x00"} {
		if _, err := checkFolder(bad); err == nil {
			t.Errorf("checkFolder(%q) accepted it", bad)
		}
	}
}

func TestErrUnsupported(t *testing.T) {
	if !errors.Is(ErrUnsupported, ErrUnsupported) || ErrUnsupported.Error() != "not supported on this system" {
		t.Fatal("ErrUnsupported changed")
	}
}

func TestKeepAwakeReleaseIdempotent(t *testing.T) {
	release := KeepAwake()
	if release == nil {
		t.Fatal("KeepAwake returned a nil release")
	}
	var wg sync.WaitGroup
	for range 4 {
		wg.Add(1)
		go func() { defer wg.Done(); release() }()
	}
	wg.Wait()
	release()
	release()
}

func TestOnceRelease(t *testing.T) {
	n := 0
	r := onceRelease(func() { n++ })
	r()
	r()
	if n != 1 {
		t.Fatalf("ran %d times", n)
	}
	onceRelease(nil)() // must not panic
}
