package blackbox

// Where files go and never-overwrite naming: PDF 10.6 (page 81).

import (
	"bytes"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"
	"unicode/utf8"

	"galleryslim/receiver/internal/receive"
	"galleryslim/receiver/internal/store"
)

func openFolder(t *testing.T, root string) *store.Folder {
	t.Helper()
	f, err := store.Open(root)
	if err != nil {
		t.Fatal(err)
	}
	return f
}

var partSeq int

// finalize writes content as a part file and moves it into place under name.
func finalize(t *testing.T, f *store.Folder, month, name string, content []byte, mtime time.Time) (string, error) {
	t.Helper()
	partSeq++
	pp, err := f.PartPath(store.PartName("phone", fmt.Sprintf("id%d", partSeq), int64(len(content)), 0, ""))
	if err != nil {
		t.Fatal(err)
	}
	os.MkdirAll(filepath.Dir(pp), 0o755)
	if err := os.WriteFile(pp, content, 0o644); err != nil {
		t.Fatal(err)
	}
	return f.Finalize(pp, month, name, mtime)
}

// RW-01: <folder>/yyyy-MM/name, or undated/ when the month is unknown or malformed; month
// folders sit directly inside the chosen folder.
func TestRW01_MonthFolders(t *testing.T) {
	for in, want := range map[string]string{
		"2024-01": "2024-01", "1999-12": "1999-12", "": "undated", "undated": "undated",
		"2024-13": "undated", "2024-00": "undated", "2024-1": "undated", "24-01": "undated",
		"../2024-01": "undated", "2024-01/..": "undated", "2024/01": "undated",
	} {
		if got := store.MonthDir(in); got != want {
			t.Errorf("month %q → %q, want %q", in, got, want)
		}
	}
	root := t.TempDir()
	f := openFolder(t, root)
	rel, err := finalize(t, f, "2024-01", "IMG_1.jpg", []byte("x"), time.Time{})
	if err != nil || rel != "2024-01/IMG_1.jpg" {
		t.Fatalf("rel %q err %v", rel, err)
	}
	if !fileExists(filepath.Join(root, "2024-01", "IMG_1.jpg")) {
		t.Error("file not directly inside <folder>/2024-01/")
	}
	if rel, _ := finalize(t, f, "1970-1x", "a.jpg", []byte("x"), time.Time{}); rel != "undated/a.jpg" {
		t.Errorf("malformed month saved as %q", rel)
	}
}

// RW-02: modification time = date taken, unless ≤ 0 or more than 24 h in the future.
func TestRW02_ModificationTime(t *testing.T) {
	now := time.Now()
	future := now.Add(72 * time.Hour).UnixMilli()
	soon := now.Add(2 * time.Hour).UnixMilli()
	p := newFakePhone(t,
		&phoneFile{ID: "1", Name: "taken.jpg", Mime: "image/jpeg", Taken: jan2024, Data: []byte("a")},
		&phoneFile{ID: "2", Name: "zero.jpg", Mime: "image/jpeg", Taken: 0, Data: []byte("b")},
		&phoneFile{ID: "3", Name: "neg.jpg", Mime: "image/jpeg", Taken: -86_400_000, Month: "undated", Data: []byte("c")},
		&phoneFile{ID: "4", Name: "future.jpg", Mime: "image/jpeg", Taken: future, Data: []byte("d")},
		&phoneFile{ID: "5", Name: "soon.jpg", Mime: "image/jpeg", Taken: soon, Data: []byte("e")},
	)
	folder := t.TempDir()
	out := runEngine(t, ctxFor(t, 60*time.Second), p, folder, nil)
	if out.sum.Verified != 5 {
		t.Fatalf("sum %+v err %v", out.sum, out.err)
	}
	mt := func(id string) time.Time {
		r, _ := out.col.byID(id)
		fi, err := os.Stat(filepath.Join(folder, filepath.FromSlash(r.Rel)))
		if err != nil {
			t.Fatalf("file %s: %v", id, err)
		}
		return fi.ModTime()
	}
	if got := mt("1"); !got.Equal(time.UnixMilli(jan2024)) {
		t.Errorf("mtime %v, want date taken %v", got, time.UnixMilli(jan2024))
	}
	for _, id := range []string{"2", "3", "4"} {
		if got := mt(id); got.Before(now.Add(-time.Minute)) || got.After(now.Add(time.Hour)) {
			t.Errorf("file %s: mtime %v should be left as the save time", id, got)
		}
	}
	if got := mt("5"); !got.Equal(time.UnixMilli(soon)) {
		t.Errorf("date taken 2 h ahead (within 24 h) not used: %v", got)
	}
	// RW-03: hidden folder with parts/ and manifest.json.
	if !fileExists(filepath.Join(folder, ".gallery-slim", "manifest.json")) {
		t.Error("RW-03: no .gallery-slim/manifest.json")
	}
	if !fileExists(filepath.Join(folder, ".gallery-slim", "parts")) {
		t.Error("RW-03: no .gallery-slim/parts/")
	}
}

// RW-04: never overwrite: "name (2).ext" …; existing files are untouched.
func TestRW04_NeverOverwrite(t *testing.T) {
	root := t.TempDir()
	os.MkdirAll(filepath.Join(root, "2024-01"), 0o755)
	os.WriteFile(filepath.Join(root, "2024-01", "IMG_1.jpg"), []byte("someone else's photo"), 0o644)
	f := openFolder(t, root)
	rel, err := finalize(t, f, "2024-01", "IMG_1.jpg", []byte("new"), time.Time{})
	if err != nil || rel != "2024-01/IMG_1 (2).jpg" {
		t.Errorf("rel %q err %v, want 2024-01/IMG_1 (2).jpg", rel, err)
	}
	rel, _ = finalize(t, f, "2024-01", "IMG_1.jpg", []byte("newer"), time.Time{})
	if rel != "2024-01/IMG_1 (3).jpg" {
		t.Errorf("third copy %q", rel)
	}
	if b, _ := os.ReadFile(filepath.Join(root, "2024-01", "IMG_1.jpg")); string(b) != "someone else's photo" {
		t.Error("existing file was replaced")
	}
	// A folder with the file's name is also never replaced.
	os.MkdirAll(filepath.Join(root, "2024-01", "dir.jpg"), 0o755)
	if rel, _ := finalize(t, f, "2024-01", "dir.jpg", []byte("x"), time.Time{}); rel != "2024-01/dir (2).jpg" {
		t.Errorf("name taken by a folder → %q", rel)
	}
}

// RW-05: names up to "(9999)"; beyond that nothing is overwritten.
func TestRW05_SuffixLimit(t *testing.T) {
	if testing.Short() {
		t.Skip("creates 10,000 files")
	}
	root := t.TempDir()
	dir := filepath.Join(root, "2024-01")
	os.MkdirAll(dir, 0o755)
	os.WriteFile(filepath.Join(dir, "a.jpg"), []byte("0"), 0o644)
	for n := 2; n <= 9998; n++ {
		os.WriteFile(filepath.Join(dir, fmt.Sprintf("a (%d).jpg", n)), []byte("0"), 0o644)
	}
	f := openFolder(t, root)
	rel, err := finalize(t, f, "2024-01", "a.jpg", []byte("last"), time.Time{})
	if err != nil || rel != "2024-01/a (9999).jpg" {
		t.Errorf("rel %q err %v, want a (9999).jpg", rel, err)
	}
	rel, err = finalize(t, f, "2024-01", "a.jpg", []byte("over"), time.Time{})
	if err == nil {
		t.Errorf("a 10,000th copy was saved as %q", rel)
	}
	if b, _ := os.ReadFile(filepath.Join(dir, "a.jpg")); string(b) != "0" {
		t.Error("a.jpg was overwritten")
	}
}

// RW-06: names differing only in letter case count as taken on Windows and macOS (not on Linux).
func TestRW06_CaseOnlyClash(t *testing.T) {
	t.Run("linux", func(t *testing.T) {
		if store.CaseInsensitiveOS {
			t.Skip("not Linux")
		}
		root := t.TempDir()
		f := openFolder(t, root)
		finalize(t, f, "2024-01", "img_1.jpg", []byte("a"), time.Time{})
		rel, _ := finalize(t, f, "2024-01", "IMG_1.JPG", []byte("b"), time.Time{})
		if rel != "2024-01/IMG_1.JPG" {
			t.Errorf("case-only different name on Linux saved as %q", rel)
		}
	})
	t.Run("windows/macOS behaviour", func(t *testing.T) {
		root := t.TempDir()
		f := openFolder(t, root)
		f.CaseInsensitive = true
		finalize(t, f, "2024-01", "img_1.jpg", []byte("a"), time.Time{})
		rel, _ := finalize(t, f, "2024-01", "IMG_1.JPG", []byte("b"), time.Time{})
		if rel != "2024-01/IMG_1 (2).JPG" {
			t.Errorf("case-only clash saved as %q, want IMG_1 (2).JPG", rel)
		}
		if b, _ := os.ReadFile(filepath.Join(root, "2024-01", "img_1.jpg")); string(b) != "a" {
			t.Error("first file replaced")
		}
	})
}

// RW-07 / RW-08 / RW-09: name cleaning on all systems.
func TestRW07_NameCleaning(t *testing.T) {
	cases := []struct{ in, mime, want string }{
		{`a<b>c:d"e/f\g|h?i*j.jpg`, "image/jpeg", "a_b_c_d_e_f_g_h_i_j.jpg"},
		{"‮evil\u0007‏.jpg", "image/jpeg", "evil.jpg"},
		{"tab\there.jpg", "image/jpeg", "tabhere.jpg"},
		{"photo.jpg. . ", "image/jpeg", "photo.jpg"},
		{"   lead.jpg", "image/jpeg", "lead.jpg"},
		{".hidden.jpg", "image/jpeg", "_.hidden.jpg"},
		{"..", "image/jpeg", "photo.jpg"},
		{"", "image/jpeg", "photo.jpg"},
		{"‮", "video/mp4", "photo.mp4"},
		{"CON.jpg", "image/jpeg", "_CON.jpg"},
		{"con.jpg", "image/jpeg", "_con.jpg"},
		{"PRN.mp4", "video/mp4", "_PRN.mp4"},
		{"AUX.jpg", "image/jpeg", "_AUX.jpg"},
		{"NUL", "image/jpeg", "_NUL.jpg"},
		{"CONIN$.jpg", "image/jpeg", "_CONIN$.jpg"},
		{"CONOUT$.jpg", "image/jpeg", "_CONOUT$.jpg"},
		{"COM0.jpg", "image/jpeg", "_COM0.jpg"},
		{"COM9.jpg", "image/jpeg", "_COM9.jpg"},
		{"LPT1.jpg", "image/jpeg", "_LPT1.jpg"},
		{"COM¹.jpg", "image/jpeg", "_COM¹.jpg"},
		{"LPT³.jpg", "image/jpeg", "_LPT³.jpg"},
		{"CONSOLE.jpg", "image/jpeg", "CONSOLE.jpg"},
		{"IMG_1", "image/jpeg", "IMG_1.jpg"},
		{"VID_2", "video/mp4", "VID_2.mp4"},
		{"IMG_3", "image/heic", "IMG_3.heic"},
		{"फोटो दिवाली.jpg", "image/jpeg", "फोटो दिवाली.jpg"},
		{"छुट्टियाँ 2024", "image/jpeg", "छुट्टियाँ 2024.jpg"},
	}
	for _, c := range cases {
		if got := store.CleanName(c.in, c.mime); got != c.want {
			t.Errorf("CleanName(%q, %s) = %q, want %q", c.in, c.mime, got, c.want)
		}
	}
	// RW-09: at most 255 bytes, valid UTF-8, extension kept — also with a suffix.
	for _, in := range []string{strings.Repeat("a", 300) + ".jpg", strings.Repeat("फ", 120) + ".jpeg", strings.Repeat("😀", 80) + ".mp4"} {
		got := store.CleanName(in, "")
		if len(got) > 255 || !utf8.ValidString(got) || filepath.Ext(got) != filepath.Ext(in) {
			t.Errorf("long name → %d bytes, valid %v, ext %q", len(got), utf8.ValidString(got), filepath.Ext(got))
		}
		s := store.WithSuffix(got, 9999)
		if len(s) > 255 || !utf8.ValidString(s) || !strings.Contains(s, " (9999)") {
			t.Errorf("long name with suffix → %q (%d bytes)", s, len(s))
		}
	}
}

// RW-08 / RW-07 end to end: odd names arrive on disk as the PDF says.
func TestRW08_NamesOnDisk(t *testing.T) {
	p := newFakePhone(t,
		&phoneFile{ID: "1", Name: "दिवाली की रात.jpg", Mime: "image/jpeg", Taken: jan2024, Data: []byte("1")},
		&phoneFile{ID: "2", Name: "CON.jpg", Mime: "image/jpeg", Taken: jan2024, Data: []byte("2")},
		&phoneFile{ID: "3", Name: "../../evil.jpg", Mime: "image/jpeg", Taken: jan2024, Data: []byte("3")},
		&phoneFile{ID: "4", Name: "IMG_5", Mime: "image/jpeg", Taken: jan2024, Data: []byte("4")},
		&phoneFile{ID: "5", Name: strings.Repeat("ल", 200) + ".jpg", Mime: "image/jpeg", Taken: jan2024, Data: []byte("5")},
		&phoneFile{ID: "6", Name: "same.jpg", Mime: "image/jpeg", Taken: jan2024, Data: []byte("6")},
		&phoneFile{ID: "7", Name: "same.jpg", Mime: "image/jpeg", Taken: jan2024, Data: []byte("7")},
	)
	root := t.TempDir()
	out := runEngine(t, ctxFor(t, 60*time.Second), p, root, nil)
	if out.sum.Verified != 7 {
		t.Fatalf("sum %+v err %v", out.sum, out.err)
	}
	got := savedFiles(t, root)
	for _, want := range []string{"2024-01/दिवाली की रात.jpg", "2024-01/_CON.jpg", "2024-01/_.._.._evil.jpg", "2024-01/IMG_5.jpg", "2024-01/same.jpg", "2024-01/same (2).jpg"} {
		if _, ok := got[want]; !ok {
			t.Errorf("%q not on disk; have %v", want, keys(got))
		}
	}
	if b := got["2024-01/same.jpg"]; !bytes.Equal(b, []byte("6")) && !bytes.Equal(b, []byte("7")) {
		t.Error("same.jpg content wrong")
	}
	for name := range got {
		if !strings.HasPrefix(name, "2024-01/") {
			t.Errorf("file escaped its month folder: %s", name)
		}
		if len(filepath.Base(name)) > 255 {
			t.Errorf("name over 255 bytes: %d", len(filepath.Base(name)))
		}
	}
}

// RW-10: one lock per folder — two receivers saving the same name into one folder at once
// still never overwrite.
func TestRW10_ConcurrentSaves(t *testing.T) {
	root := t.TempDir()
	a, b := openFolder(t, root), openFolder(t, root)
	var wg sync.WaitGroup
	var mu sync.Mutex
	rels := map[string]string{}
	for i := 0; i < 40; i++ {
		f := a
		if i%2 == 1 {
			f = b
		}
		content := fmt.Sprintf("content-%d", i)
		pp, _ := f.PartPath(store.PartName("phone", fmt.Sprintf("c%d", i), int64(len(content)), 0, ""))
		os.MkdirAll(filepath.Dir(pp), 0o755)
		os.WriteFile(pp, []byte(content), 0o644)
		wg.Add(1)
		go func() {
			defer wg.Done()
			rel, err := f.Finalize(pp, "2024-01", "IMG.jpg", time.Time{})
			if err != nil {
				t.Error(err)
				return
			}
			mu.Lock()
			rels[rel] = content
			mu.Unlock()
		}()
	}
	wg.Wait()
	if len(rels) != 40 {
		t.Fatalf("%d distinct names for 40 saves", len(rels))
	}
	for rel, content := range rels {
		if b, _ := os.ReadFile(filepath.Join(root, rel)); string(b) != content {
			t.Errorf("%s holds %q, want %q (overwritten)", rel, b, content)
		}
	}
}

// RW-11: files from another phone with the same names never replace earlier ones.
func TestRW11_AnotherPhoneSameNames(t *testing.T) {
	root := t.TempDir()
	a := newFakePhone(t, &phoneFile{ID: "1", Name: "IMG_1.jpg", Mime: "image/jpeg", Taken: jan2024, Data: []byte("phone A")})
	a.Name = "Phone A"
	b := newFakePhone(t, &phoneFile{ID: "1", Name: "IMG_1.jpg", Mime: "image/jpeg", Taken: jan2024, Data: []byte("phone B")})
	b.Name = "Phone B"
	if o := runEngine(t, ctxFor(t, 30*time.Second), a, root, nil); o.sum.Verified != 1 {
		t.Fatal(o.sum, o.err)
	}
	if o := runEngine(t, ctxFor(t, 30*time.Second), b, root, nil); o.sum.Verified != 1 {
		t.Fatal(o.sum, o.err)
	}
	got := savedFiles(t, root)
	if string(got["2024-01/IMG_1.jpg"]) != "phone A" || string(got["2024-01/IMG_1 (2).jpg"]) != "phone B" {
		t.Errorf("saved %v", keys(got))
	}
	_ = receive.Verified
}
