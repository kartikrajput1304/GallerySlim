//go:build unix && !darwin

package desktop

import (
	"errors"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"
)

// realSh is looked up before any test replaces PATH.
var realSh, _ = exec.LookPath("sh")

// fakeTools puts shell scripts named after desktop programs into a fresh
// folder and makes that folder the whole PATH (plus the real cat/sh location
// for the scripts themselves). Each script logs its arguments, one per line,
// to <dir>/<name>.args.
func fakeTools(t *testing.T, scripts map[string]string) string {
	t.Helper()
	sh := realSh
	if sh == "" {
		t.Skip("no sh")
	}
	dir := t.TempDir()
	for name, body := range scripts {
		script := "#!" + sh + "\nfor a in \"$@\"; do printf '%s\\n' \"$a\"; done > \"" + filepath.Join(dir, name+".args") + "\"\n" + body + "\n"
		if err := os.WriteFile(filepath.Join(dir, name), []byte(script), 0o755); err != nil {
			t.Fatal(err)
		}
	}
	t.Setenv("PATH", dir)
	return dir
}

func loggedArgs(t *testing.T, dir, name string) []string {
	t.Helper()
	b, err := os.ReadFile(filepath.Join(dir, name+".args"))
	if err != nil {
		t.Fatalf("%s was not run: %v", name, err)
	}
	return strings.Split(strings.TrimSuffix(string(b), "\n"), "\n")
}

func TestPickFolderZenity(t *testing.T) {
	const chosen = "/home/asha/चित्र/My \"Photos\""
	dir := fakeTools(t, map[string]string{"zenity": "printf '%s\\n' '" + chosen + "'"})
	t.Setenv("DISPLAY", ":0")
	t.Setenv("XDG_CURRENT_DESKTOP", "GNOME")
	start := t.TempDir()

	got, err := PickFolder(`Pick "a" folder; $(rm -rf ~)`, start)
	if err != nil || got != chosen {
		t.Fatalf("PickFolder = %q, %v; want %q", got, err, chosen)
	}
	args := loggedArgs(t, dir, "zenity")
	want := []string{"--file-selection", "--directory", `--title=Pick "a" folder; $(rm -rf ~)`, "--filename=" + start + "/"}
	if strings.Join(args, "|") != strings.Join(want, "|") {
		t.Fatalf("zenity args %q, want %q", args, want)
	}
}

func TestPickFolderCancelAndFailure(t *testing.T) {
	t.Setenv("DISPLAY", "")
	t.Setenv("WAYLAND_DISPLAY", "wayland-0")
	t.Setenv("XDG_CURRENT_DESKTOP", "")

	fakeTools(t, map[string]string{"zenity": "exit 1"})
	if got, err := PickFolder("t", ""); err != nil || got != "" {
		t.Fatalf("cancel: got %q, %v; want \"\", nil", got, err)
	}

	fakeTools(t, map[string]string{"zenity": "echo 'Gtk-WARNING: cannot open display' >&2; exit 5"})
	if _, err := PickFolder("t", ""); err == nil || !strings.Contains(err.Error(), "cannot open display") {
		t.Fatalf("failure: got %v", err)
	}

	fakeTools(t, map[string]string{"zenity": "echo relative/path"})
	if _, err := PickFolder("t", ""); err == nil {
		t.Fatal("accepted a relative path from the dialog")
	}
}

func TestPickFolderKDialogOnKDE(t *testing.T) {
	dir := fakeTools(t, map[string]string{
		"kdialog": "echo /home/asha/Pictures/",
		"zenity":  "echo /wrong",
	})
	t.Setenv("DISPLAY", ":0")
	t.Setenv("XDG_CURRENT_DESKTOP", "KDE")
	got, err := PickFolder("Choose", "/no/such/folder")
	if err != nil || got != "/home/asha/Pictures" {
		t.Fatalf("PickFolder = %q, %v", got, err)
	}
	args := loggedArgs(t, dir, "kdialog")
	if len(args) != 4 || args[0] != "--getexistingdirectory" || !filepath.IsAbs(args[1]) || args[2] != "--title" || args[3] != "Choose" {
		t.Fatalf("kdialog args %q", args)
	}
	if args[1] == "/no/such/folder" {
		t.Fatal("a missing start folder was passed on")
	}
}

func TestPickFolderUnsupported(t *testing.T) {
	fakeTools(t, map[string]string{"zenity": "echo /x"})
	t.Setenv("DISPLAY", "")
	t.Setenv("WAYLAND_DISPLAY", "")
	if _, err := PickFolder("t", ""); !errors.Is(err, ErrUnsupported) {
		t.Fatalf("no display: got %v", err)
	}
	fakeTools(t, nil)
	t.Setenv("DISPLAY", ":0")
	if _, err := PickFolder("t", ""); !errors.Is(err, ErrUnsupported) {
		t.Fatalf("no dialog program: got %v", err)
	}
}

func TestOpenURLAndFolder(t *testing.T) {
	dir := fakeTools(t, map[string]string{"xdg-open": "exit 0"})
	if err := OpenURL("http://127.0.0.1:41234/tok/"); err != nil {
		t.Fatal(err)
	}
	if args := loggedArgs(t, dir, "xdg-open"); len(args) != 1 || args[0] != "http://127.0.0.1:41234/tok/" {
		t.Fatalf("xdg-open args %q", args)
	}
	folder := filepath.Join(t.TempDir(), "चित्र")
	if err := os.Mkdir(folder, 0o700); err != nil {
		t.Fatal(err)
	}
	if err := OpenFolder(folder); err != nil {
		t.Fatal(err)
	}
	if args := loggedArgs(t, dir, "xdg-open"); len(args) != 1 || args[0] != folder {
		t.Fatalf("xdg-open args %q", args)
	}
	if err := OpenURL("file:///etc/passwd"); err == nil {
		t.Fatal("opened a file: link")
	}
	if err := OpenFolder("/etc/passwd"); err == nil {
		t.Fatal("opened a file as a folder")
	}

	fakeTools(t, map[string]string{"xdg-open": "exit 3"})
	if err := OpenURL("https://example.com/"); err == nil {
		t.Fatal("xdg-open failure not reported")
	}
	fakeTools(t, nil)
	if err := OpenURL("https://example.com/"); !errors.Is(err, ErrUnsupported) {
		t.Fatalf("no xdg-open: got %v", err)
	}
}

func TestDefaultFolderXDG(t *testing.T) {
	home := t.TempDir()
	t.Setenv("HOME", home)

	fakeTools(t, map[string]string{"xdg-user-dir": "echo " + home + "/चित्र"})
	if got, note := DefaultFolder(); got != home+"/चित्र/GallerySlim Photos" || note != "" {
		t.Fatalf("got %q, %q", got, note)
	}
	// xdg-user-dir answers $HOME when Pictures isn't configured.
	fakeTools(t, map[string]string{"xdg-user-dir": "echo " + home})
	if got, _ := DefaultFolder(); got != filepath.Join(home, "Pictures", "GallerySlim Photos") {
		t.Fatalf("got %q", got)
	}
	fakeTools(t, map[string]string{"xdg-user-dir": "echo " + home + "/Dropbox/Pictures"})
	got, note := DefaultFolder()
	if got != filepath.Join(home, "GallerySlim Photos") || !strings.Contains(note, "Dropbox") {
		t.Fatalf("got %q, %q", got, note)
	}
	fakeTools(t, nil)
	if got, _ := DefaultFolder(); got != filepath.Join(home, "Pictures", "GallerySlim Photos") {
		t.Fatalf("no xdg-user-dir: got %q", got)
	}
}

func TestCloudSyncedFollowsSymlink(t *testing.T) {
	home := t.TempDir()
	t.Setenv("HOME", home)
	target := filepath.Join(home, "Dropbox", "Photos")
	if err := os.MkdirAll(target, 0o700); err != nil {
		t.Fatal(err)
	}
	link := filepath.Join(home, "Pictures")
	if err := os.Symlink(target, link); err != nil {
		t.Fatal(err)
	}
	if ok, prov := CloudSynced(link); !ok || prov != "Dropbox" {
		t.Fatalf("CloudSynced(%q) = %v, %q", link, ok, prov)
	}
	if ok, _ := CloudSynced(filepath.Join(home, "Elsewhere")); ok {
		t.Fatal("plain folder reported as synced")
	}
}

func TestConfigDir(t *testing.T) {
	base := t.TempDir()
	t.Setenv("XDG_CONFIG_HOME", base)
	want := filepath.Join(base, "GallerySlim Receiver")
	if err := os.Mkdir(want, 0o755); err != nil { // pre-existing with loose permissions
		t.Fatal(err)
	}
	got, err := ConfigDir()
	if err != nil || got != want {
		t.Fatalf("ConfigDir = %q, %v; want %q", got, err, want)
	}
	fi, err := os.Stat(got)
	if err != nil || fi.Mode().Perm() != 0o700 {
		t.Fatalf("mode %v, %v; want 0700", fi.Mode().Perm(), err)
	}
	if err := os.Remove(got); err != nil {
		t.Fatal(err)
	}
	if got, err := ConfigDir(); err != nil || got != want {
		t.Fatalf("fresh ConfigDir = %q, %v", got, err)
	}
	if fi, err := os.Stat(want); err != nil || fi.Mode().Perm() != 0o700 {
		t.Fatalf("fresh mode %v, %v", fi.Mode().Perm(), err)
	}
}

func TestStartHelperStdinEOF(t *testing.T) {
	cat, err := exec.LookPath("cat")
	if err != nil {
		t.Skip("no cat")
	}
	cmd := exec.Command(cat)
	release, err := startHelper(cmd, true)
	if err != nil {
		t.Fatal(err)
	}
	began := time.Now()
	var wg sync.WaitGroup
	for range 3 {
		wg.Add(1)
		go func() { defer wg.Done(); release() }()
	}
	wg.Wait()
	release()
	if cmd.ProcessState == nil {
		t.Fatal("helper still running after release")
	}
	if !cmd.ProcessState.Success() {
		t.Fatalf("cat should exit cleanly on end of input, got %v", cmd.ProcessState)
	}
	if d := time.Since(began); d >= helperGrace {
		t.Fatalf("release took %v; cat was killed instead of ending on EOF", d)
	}
}

func TestStartHelperKill(t *testing.T) {
	sleep, err := exec.LookPath("sleep")
	if err != nil {
		t.Skip("no sleep")
	}
	cmd := exec.Command(sleep, "60")
	release, err := startHelper(cmd, false)
	if err != nil {
		t.Fatal(err)
	}
	release()
	release()
	if cmd.ProcessState == nil || cmd.ProcessState.Success() {
		t.Fatalf("sleep not killed: %v", cmd.ProcessState)
	}
}

func TestKeepAwakeFakeInhibit(t *testing.T) {
	// Behaves like systemd-inhibit: skip the options, run the command.
	cat, err := exec.LookPath("cat")
	if err != nil {
		t.Skip("no cat")
	}
	dir := fakeTools(t, map[string]string{"systemd-inhibit": `while [ $# -gt 0 ]; do case "$1" in --*) shift;; *) break;; esac; done; exec "$@"`})
	if err := os.Symlink(cat, filepath.Join(dir, "cat")); err != nil {
		t.Fatal(err)
	}
	release := KeepAwake()
	// The helper runs in the background; wait for it to log its arguments.
	for deadline := time.Now().Add(5 * time.Second); time.Now().Before(deadline); time.Sleep(10 * time.Millisecond) {
		if b, err := os.ReadFile(filepath.Join(dir, "systemd-inhibit.args")); err == nil && strings.Count(string(b), "\n") >= 5 {
			break
		}
	}
	args := loggedArgs(t, dir, "systemd-inhibit")
	want := []string{"--what=sleep:idle", "--who=GallerySlim Receiver", "--why=Receiving photos", "--mode=block", filepath.Join(dir, "cat")}
	if strings.Join(args, "|") != strings.Join(want, "|") {
		t.Fatalf("systemd-inhibit args %q, want %q", args, want)
	}
	began := time.Now()
	release()
	release()
	if d := time.Since(began); d >= helperGrace {
		t.Fatalf("release took %v", d)
	}

	fakeTools(t, nil) // no systemd-inhibit: still a usable release
	r := KeepAwake()
	r()
	r()
}
