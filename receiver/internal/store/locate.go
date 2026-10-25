package store

// Finding a file the phone wants back (gs2 §10): by SHA-256 in the manifest, then at its
// expected "<month>/<name>" place, then a bounded scan of the folder. A candidate counts only
// when its bytes on disk hash to the wanted SHA-256 right now.

import (
	"io/fs"
	"os"
	"path/filepath"
	"strings"
)

// Scan limits for Locate's last step.
const (
	locateMaxEntries = 100000 // folder entries looked at
	locateMaxHashes  = 64     // same-size files hashed
	locateMaxDepth   = 4      // folders below the root
)

// Locate returns the full path of a file in the folder whose size and SHA-256 (lower-case
// hex) match, or false.
func (f *Folder) Locate(sha string, size int64, month, name string) (string, bool) {
	sha = strings.ToLower(sha)
	tried := map[string]bool{}
	hashes := 0
	check := func(path string) bool {
		if tried[path] {
			return false
		}
		tried[path] = true
		fi, err := os.Lstat(path)
		if err != nil || !fi.Mode().IsRegular() || fi.Size() != size {
			return false
		}
		hashes++
		sum, n, err := HashFile(path)
		return err == nil && n == size && sum == sha
	}

	// 1. The manifest (only a hint: re-hashed).
	var rels []string
	f.manMu.Lock()
	for rel, e := range f.man.Files {
		if e.Size == size && strings.EqualFold(e.SHA256, sha) {
			rels = append(rels, rel)
		}
	}
	f.manMu.Unlock()
	for _, rel := range rels {
		if _, _, ok := validRel(rel); ok && check(f.Abs(rel)) {
			return f.Abs(rel), true
		}
	}

	// 2. Where the receiver would have saved it, including "name (2).jpg" and so on.
	if clean := CleanName(name, ""); clean != "" {
		dir := filepath.Join(f.Root, MonthDir(month))
		for i := 1; i <= 9; i++ {
			if check(filepath.Join(dir, WithSuffix(clean, i))) {
				return filepath.Join(dir, WithSuffix(clean, i)), true
			}
		}
	}

	// 3. A bounded scan: same-size regular files, the receiver's own folder skipped.
	found, entries := "", 0
	filepath.WalkDir(f.Root, func(p string, d fs.DirEntry, err error) error {
		if err != nil {
			if d != nil && d.IsDir() {
				return fs.SkipDir
			}
			return nil
		}
		entries++
		if entries > locateMaxEntries || hashes >= locateMaxHashes {
			return fs.SkipAll
		}
		rel, _ := filepath.Rel(f.Root, p)
		if d.IsDir() {
			if rel != "." && (d.Name() == MetaDir || strings.Count(rel, string(filepath.Separator)) >= locateMaxDepth) {
				return fs.SkipDir
			}
			return nil
		}
		if !d.Type().IsRegular() {
			return nil
		}
		if info, err := d.Info(); err == nil && info.Size() == size && check(p) {
			found = p
			return fs.SkipAll
		}
		return nil
	})
	return found, found != ""
}
