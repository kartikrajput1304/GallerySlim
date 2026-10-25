package store

import (
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"
	"unicode/utf8"
)

func TestCleanName(t *testing.T) {
	cases := []struct{ in, mime, want string }{
		{"IMG_1.jpg", "", "IMG_1.jpg"},
		{"CON.jpg", "", "_CON.jpg"},
		{"con", "image/jpeg", "_con.jpg"},
		{"nul.tar.gz", "", "_nul.tar.gz"},
		{"LPT1 .png", "", "_LPT1.png"},
		{"COM9.mp4", "", "_COM9.mp4"},
		{"com¹.jpg", "", "_com¹.jpg"},
		{"CONOUT$.jpg", "", "_CONOUT$.jpg"},
		{"CONSOLE.jpg", "", "CONSOLE.jpg"},
		{`a<b>:c"d/e\f|g?h*.jpg`, "", "a_b__c_d_e_f_g_h_.jpg"},
		{"photo.jpg...", "", "photo.jpg"},
		{"  spaced .jpg  ", "", "spaced.jpg"},
		{"..", "image/jpeg", "photo.jpg"},
		{".", "", "photo"},
		{"../../etc/passwd", "", "_.._._etc_passwd"},
		{".hidden.jpg", "", "_.hidden.jpg"},
		{"IMG\x00\x1f_1\x7f.jpg", "", "IMG_1.jpg"},
		{"photo‮gpj.exe", "", "photogpj.exe"},
		{"IMG_2", "image/heic", "IMG_2.heic"},
		{"VID_1", "video/mp4", "VID_1.mp4"},
		{"", "", "photo"},
		{"bad\xffutf8.jpg", "", "badutf8.jpg"},
	}
	for _, c := range cases {
		if got := CleanName(c.in, c.mime); got != c.want {
			t.Errorf("CleanName(%q, %q) = %q, want %q", c.in, c.mime, got, c.want)
		}
	}
}

func TestCleanNameLengthAndIdempotence(t *testing.T) {
	long := strings.Repeat("写", 120) + ".jpg" // 364 bytes
	got := CleanName(long, "")
	if len(got) > MaxNameBytes || !utf8.ValidString(got) || !strings.HasSuffix(got, ".jpg") {
		t.Fatalf("long name: %d bytes, %q", len(got), got)
	}
	s2 := WithSuffix(got, 1234)
	if len(s2) > MaxNameBytes || !utf8.ValidString(s2) || !strings.HasSuffix(s2, " (1234).jpg") {
		t.Fatalf("suffixed: %d bytes %q", len(s2), s2)
	}
	nasty := []string{long, "CON.jpg", " . . ", "a/b\\c", "x⁦y.png", "LPT1", "IMG.JPG.", "名前 (2).jpg", ".."}
	for _, n := range nasty {
		once := CleanName(n, "")
		if twice := CleanName(once, ""); twice != once {
			t.Errorf("not idempotent: %q → %q → %q", n, once, twice)
		}
		if strings.ContainsAny(once, `/\<>:"|?*`) || once == "" || once == "." || once == ".." {
			t.Errorf("unsafe result %q", once)
		}
	}
}

func TestWithSuffix(t *testing.T) {
	if got := WithSuffix("IMG_1.jpg", 2); got != "IMG_1 (2).jpg" {
		t.Error(got)
	}
	if got := WithSuffix("photo", 3); got != "photo (3)" {
		t.Error(got)
	}
	if got := WithSuffix("a.b", 1); got != "a.b" {
		t.Error(got)
	}
}

func TestMonthDir(t *testing.T) {
	for in, want := range map[string]string{
		"2023-11": "2023-11", "2023-13": Undated, "2023-1": Undated, "../x": Undated,
		"undated": Undated, "": Undated, "2023-11/..": Undated,
	} {
		if got := MonthDir(in); got != want {
			t.Errorf("MonthDir(%q) = %q", in, got)
		}
	}
}

func TestPartName(t *testing.T) {
	a := PartName("Pixel", "42", 100, 5, "")
	if a != PartName("Pixel", "42", 100, 5, "") {
		t.Error("not deterministic")
	}
	if a == PartName("Galaxy", "42", 100, 5, "") || a == PartName("Pixel", "42", 101, 5, "") {
		t.Error("collides")
	}
	if !strings.HasPrefix(a, "42-100-") || !strings.HasSuffix(a, ".part") {
		t.Error(a)
	}
	if b := PartName("p", "../../x", 1, 0, ""); strings.ContainsAny(b, `/\`) || !strings.HasPrefix(b, "f-1-") {
		t.Error(b)
	}
}

func writePart(t *testing.T, f *Folder, name, content string) string {
	t.Helper()
	p := filepath.Join(f.partsDir(), name) // test names are not PartName-shaped on purpose
	if err := os.WriteFile(p, []byte(content), 0o644); err != nil {
		t.Fatal(err)
	}
	return p
}

func readFile(t *testing.T, p string) string {
	t.Helper()
	b, err := os.ReadFile(p)
	if err != nil {
		t.Fatal(err)
	}
	return string(b)
}

func TestFinalizeBasicAndClash(t *testing.T) {
	f, err := Open(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	taken := time.Date(2023, 11, 5, 10, 0, 0, 0, time.UTC)
	p := writePart(t, f, "a.part", "first")
	rel, err := f.Finalize(p, "2023-11", "IMG_1.jpg", taken)
	if err != nil || rel != "2023-11/IMG_1.jpg" {
		t.Fatalf("%v %q", err, rel)
	}
	if _, err := os.Stat(p); !os.IsNotExist(err) {
		t.Error("part file still there")
	}
	fi, _ := os.Stat(f.Abs(rel))
	if !fi.ModTime().Equal(taken) {
		t.Errorf("mtime %v", fi.ModTime())
	}
	p2 := writePart(t, f, "b.part", "second")
	rel2, err := f.Finalize(p2, "2023-11", "IMG_1.jpg", time.Time{})
	if err != nil || rel2 != "2023-11/IMG_1 (2).jpg" {
		t.Fatalf("%v %q", err, rel2)
	}
	if readFile(t, f.Abs(rel)) != "first" || readFile(t, f.Abs(rel2)) != "second" {
		t.Error("contents mixed up")
	}
	if _, err := f.Finalize(writePart(t, f, "c.part", "x"), "bad", "../evil", time.Time{}); err == nil {
		t.Error("unclean name accepted")
	}
}

func TestFinalizeCaseInsensitive(t *testing.T) {
	f, _ := Open(t.TempDir())
	f.CaseInsensitive = true
	os.MkdirAll(filepath.Join(f.Root, "2024-01"), 0o755)
	os.WriteFile(filepath.Join(f.Root, "2024-01", "img_7.JPG"), []byte("theirs"), 0o644)
	rel, err := f.Finalize(writePart(t, f, "a.part", "ours"), "2024-01", "IMG_7.jpg", time.Time{})
	if err != nil || rel != "2024-01/IMG_7 (2).jpg" {
		t.Fatalf("%v %q", err, rel)
	}
	if readFile(t, filepath.Join(f.Root, "2024-01", "img_7.JPG")) != "theirs" {
		t.Error("existing file touched")
	}
}

func testPlaceholderReplaced(t *testing.T, fallback bool) {
	f, _ := Open(t.TempDir())
	testForceFallback = fallback
	calls := 0
	testBeforeMove = func(path string) {
		calls++
		if calls == 1 {
			// Another program replaces our empty placeholder with its own file.
			tmp := path + ".other"
			os.WriteFile(tmp, []byte("unrelated"), 0o644)
			os.Rename(tmp, path)
		}
	}
	defer func() { testBeforeMove = nil; testForceFallback = false }()
	part := writePart(t, f, "a.part", "ours")
	rel, err := f.Finalize(part, "2023-01", "X.jpg", time.Time{})
	if err != nil || rel != "2023-01/X (2).jpg" {
		t.Fatalf("%v %q", err, rel)
	}
	if got := readFile(t, filepath.Join(f.Root, "2023-01", "X.jpg")); got != "unrelated" {
		t.Errorf("unrelated file was replaced: %q", got)
	}
	if got := readFile(t, f.Abs(rel)); got != "ours" {
		t.Errorf("ours = %q", got)
	}
	if _, err := os.Stat(part); !os.IsNotExist(err) {
		t.Error("part left behind")
	}
}

func TestFinalizeNeverReplacesUnrelatedFile(t *testing.T) {
	t.Run("native", func(t *testing.T) { testPlaceholderReplaced(t, false) })
	t.Run("fallback", func(t *testing.T) { testPlaceholderReplaced(t, true) })
}

func TestFinalizeConcurrent(t *testing.T) {
	root := t.TempDir()
	var wg sync.WaitGroup
	results := make([]string, 20)
	for i := 0; i < 20; i++ {
		wg.Add(1)
		go func(i int) {
			defer wg.Done()
			f, err := Open(root) // separate Folder values share the per-folder lock
			if err != nil {
				t.Error(err)
				return
			}
			p := writePart(t, f, fmt.Sprintf("%d.part", i), fmt.Sprint(i))
			rel, err := f.Finalize(p, "2022-02", "same.jpg", time.Time{})
			if err != nil {
				t.Error(err)
				return
			}
			results[i] = rel
		}(i)
	}
	wg.Wait()
	seen := map[string]bool{}
	f, _ := Open(root)
	for i, rel := range results {
		if seen[rel] {
			t.Fatalf("duplicate name %q", rel)
		}
		seen[rel] = true
		if got := readFile(t, f.Abs(rel)); got != fmt.Sprint(i) {
			t.Errorf("%s holds %q, want %d", rel, got, i)
		}
	}
}

func sha(s string) string {
	h := sha256.Sum256([]byte(s))
	return hex.EncodeToString(h[:])
}

func TestManifestAndFindExisting(t *testing.T) {
	root := t.TempDir()
	f, _ := Open(root)
	rel, err := f.Finalize(writePart(t, f, "a.part", "hello"), "2023-05", "A.jpg", time.Time{})
	if err != nil {
		t.Fatal(err)
	}
	f.Record(rel, ManifestEntry{Size: 5, SHA256: sha("hello"), FileID: "7", Phone: "P"})
	f.Record("../../outside", ManifestEntry{Size: 5, SHA256: sha("hello"), FileID: "8"})
	if err := f.Flush(); err != nil {
		t.Fatal(err)
	}

	g, _ := Open(root) // reload from disk
	if ex, ok := g.FindExisting("P", "7", 5, "", "2023-05", "A.jpg"); !ok || ex.Rel != rel || ex.SHA256 != sha("hello") {
		t.Fatalf("not found: %+v", ex)
	}
	if _, ok := g.FindExisting("P", "7", 6, "", "2023-05", "A.jpg"); ok {
		t.Error("size mismatch accepted")
	}
	if _, ok := g.FindExisting("P", "7", 5, sha("other"), "2023-05", "A.jpg"); ok {
		t.Error("listed hash mismatch accepted")
	}
	if _, ok := g.FindExisting("P", "8", 5, "", "2023-05", "A.jpg"); ok {
		t.Error("path outside the folder accepted")
	}
	// Changed on disk (same size): no longer counts.
	os.WriteFile(g.Abs(rel), []byte("HELLO"), 0o644)
	if _, ok := g.FindExisting("P", "7", 5, "", "2023-05", "A.jpg"); ok {
		t.Error("changed file accepted")
	}
	// Deleted.
	os.Remove(g.Abs(rel))
	if _, ok := g.FindExisting("P", "7", 5, "", "2023-05", "A.jpg"); ok {
		t.Error("deleted file accepted")
	}
	// No manifest entry, but the phone listed a hash and the file is there by name.
	os.WriteFile(filepath.Join(root, "2023-05", "B.jpg"), []byte("bbb"), 0o644)
	if ex, ok := g.FindExisting("P", "99", 3, sha("bbb"), "2023-05", "B.jpg"); !ok || ex.Rel != "2023-05/B.jpg" {
		t.Error("by-name match with listed hash not found")
	}
}

func TestPartPathRejectsForeignNames(t *testing.T) {
	f, _ := Open(t.TempDir())
	good := PartName("Pixel", "42", 100, 5, "")
	if p, err := f.PartPath(good); err != nil || filepath.Dir(p) != f.partsDir() {
		t.Fatalf("%q %v", p, err)
	}
	for _, bad := range []string{"..", ".", "", "../x.part", "a/b-1-0123456789ab.part", "x.part", "42-100-0123456789ab.part/..", `..\x-1-0123456789ab.part`} {
		if _, err := f.PartPath(bad); err == nil {
			t.Errorf("%q accepted", bad)
		}
	}
}

func TestUnconfirmedIsNotReused(t *testing.T) {
	root := t.TempDir()
	f, _ := Open(root)
	rel, _ := f.Finalize(writePart(t, f, "a.part", "hello"), "2023-05", "A.jpg", time.Time{})
	f.Record(rel, ManifestEntry{Size: 5, SHA256: sha("hello"), FileID: "7"})
	f.MarkUnconfirmed(rel)
	if _, ok := f.FindExisting("", "7", 5, "", "2023-05", "A.jpg"); ok {
		t.Error("unconfirmed copy offered as already received")
	}
	if f.LikelyHave("", "7", 5) {
		t.Error("unconfirmed copy counted for space")
	}
	if _, err := os.Stat(f.Abs(rel)); err != nil {
		t.Error("unconfirmed file was removed")
	}
}

func TestCheckWritableAndFree(t *testing.T) {
	root := filepath.Join(t.TempDir(), "new", "folder")
	if err := CheckWritable(root); err != nil {
		t.Fatal(err)
	}
	if err := CheckWritable("relative/path"); err == nil {
		t.Error("relative path accepted")
	}
	f, _ := Open(root)
	if free, err := f.FreeBytes(); err != nil || free == 0 {
		t.Errorf("free %d %v", free, err)
	}
}

func TestCleanupParts(t *testing.T) {
	f, _ := Open(t.TempDir())
	old := writePart(t, f, "old.part", "x")
	fresh := writePart(t, f, "new.part", "y")
	past := time.Now().Add(-30 * 24 * time.Hour)
	os.Chtimes(old, past, past)
	f.CleanupParts(14 * 24 * time.Hour)
	if _, err := os.Stat(old); !os.IsNotExist(err) {
		t.Error("old part kept")
	}
	if _, err := os.Stat(fresh); err != nil {
		t.Error("fresh part removed")
	}
}
