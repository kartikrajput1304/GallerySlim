package store

import (
	"encoding/json"
	"errors"
	"io"
	"os"
	"path/filepath"
	"strings"
	"time"

	"galleryslim/receiver/internal/osutil"
)

const (
	manifestName    = "manifest.json"
	maxManifestSize = 64 << 20
	manifestEvery   = 2 * time.Second
)

// ManifestEntry describes a file the receiver saved. The manifest is only a hint: a file is
// never treated as present without checking it on disk.
type ManifestEntry struct {
	Size   int64  `json:"size"`
	SHA256 string `json:"sha256"`
	FileID string `json:"fileId"`
	Phone  string `json:"phone,omitempty"`
	Saved  int64  `json:"saved,omitempty"`
	// Unconfirmed marks a copy the phone said does not match its file. It is kept on disk but
	// never used to skip a download.
	Unconfirmed bool `json:"unconfirmed,omitempty"`
}

type manifest struct {
	Version int                      `json:"version"`
	Files   map[string]ManifestEntry `json:"files"`
}

func (f *Folder) manifestPath() string { return filepath.Join(f.Root, MetaDir, manifestName) }

func (f *Folder) loadManifest() {
	f.man = manifest{Version: 1, Files: map[string]ManifestEntry{}}
	fh, err := os.Open(f.manifestPath())
	if err != nil {
		return
	}
	defer fh.Close()
	var m manifest
	if err := json.NewDecoder(io.LimitReader(fh, maxManifestSize)).Decode(&m); err != nil || m.Files == nil {
		return // unreadable: start over; files are re-checked on disk anyway
	}
	f.man.Files = m.Files
}

// Record notes a saved file in the manifest (written to disk at most every few seconds and
// on Flush).
func (f *Folder) Record(rel string, e ManifestEntry) {
	f.manMu.Lock()
	defer f.manMu.Unlock()
	e.Saved = time.Now().Unix()
	f.man.Files[rel] = e
	f.dirty = true
	if time.Since(f.lastSave) >= manifestEvery {
		_ = f.saveLocked()
	}
}

// Flush writes the manifest if it changed.
func (f *Folder) Flush() error {
	f.manMu.Lock()
	defer f.manMu.Unlock()
	if !f.dirty {
		return nil
	}
	return f.saveLocked()
}

func (f *Folder) saveLocked() error {
	b, err := json.MarshalIndent(f.man, "", " ")
	if err != nil {
		return err
	}
	dir := filepath.Join(f.Root, MetaDir)
	tmp, err := os.CreateTemp(dir, "manifest-*.tmp")
	if err != nil {
		return err
	}
	_, werr := tmp.Write(b)
	serr := tmp.Sync()
	cerr := tmp.Close()
	if err := errors.Join(werr, serr, cerr); err != nil {
		os.Remove(tmp.Name())
		return err
	}
	// The manifest is the receiver's own file, so replacing it is intended.
	if err := renameRetry(tmp.Name(), f.manifestPath()); err != nil {
		os.Remove(tmp.Name())
		return err
	}
	_ = osutil.SyncDir(dir)
	f.dirty = false
	f.lastSave = time.Now()
	return nil
}

// validRel accepts only "<month>/<clean name>" paths, so a hand-edited manifest cannot point
// outside the folder.
func validRel(rel string) (month, name string, ok bool) {
	month, name, found := strings.Cut(rel, "/")
	if !found || MonthDir(month) != month || name == "" || CleanName(name, "") != name {
		return "", "", false
	}
	return month, name, true
}

// Existing is a file already in the folder that matches a phone file.
type Existing struct {
	Rel    string
	SHA256 string
	Info   os.FileInfo
}

// FindExisting looks for a file already saved for this phone file: first through the
// manifest (same file id and size), then, when the phone listed a hash, by the expected name.
// A candidate counts only if it exists, has the right size, and its bytes on disk hash to
// the recorded (and listed, if any) SHA-256.
func (f *Folder) FindExisting(phone, fileID string, size int64, listedSHA, month, cleanName string) (Existing, bool) {
	var rels []string
	var shas []string
	f.manMu.Lock()
	for rel, e := range f.man.Files {
		if e.FileID != fileID || e.Size != size || e.Unconfirmed || (e.Phone != "" && phone != "" && e.Phone != phone) {
			continue
		}
		if listedSHA != "" && !strings.EqualFold(e.SHA256, listedSHA) {
			continue
		}
		rels = append(rels, rel)
		shas = append(shas, e.SHA256)
	}
	f.manMu.Unlock()
	if listedSHA != "" {
		rels = append(rels, MonthDir(month)+"/"+cleanName)
		shas = append(shas, listedSHA)
	}
	for i, rel := range rels {
		if _, _, ok := validRel(rel); !ok {
			continue
		}
		path := f.Abs(rel)
		fi, err := os.Lstat(path)
		if err != nil || !fi.Mode().IsRegular() || fi.Size() != size {
			continue
		}
		sum, n, err := HashFile(path)
		if err != nil || n != size || !strings.EqualFold(sum, shas[i]) {
			continue
		}
		return Existing{Rel: rel, SHA256: sum, Info: fi}, true
	}
	return Existing{}, false
}

// MarkUnconfirmed flags a saved file the phone did not confirm, so later sessions don't treat
// it as already received. The file itself is left alone.
func (f *Folder) MarkUnconfirmed(rel string) {
	f.manMu.Lock()
	defer f.manMu.Unlock()
	if e, ok := f.man.Files[rel]; ok && !e.Unconfirmed {
		e.Unconfirmed = true
		f.man.Files[rel] = e
		f.dirty = true
	}
}

// LikelyHave is a cheap check (no hashing) used only to estimate the space needed: the
// manifest lists this phone file and a file of the right size is at that path.
func (f *Folder) LikelyHave(phone, fileID string, size int64) bool {
	f.manMu.Lock()
	var rels []string
	for rel, e := range f.man.Files {
		if e.FileID == fileID && e.Size == size && !e.Unconfirmed && (e.Phone == "" || phone == "" || e.Phone == phone) {
			rels = append(rels, rel)
		}
	}
	f.manMu.Unlock()
	for _, rel := range rels {
		if _, _, ok := validRel(rel); !ok {
			continue
		}
		if fi, err := os.Lstat(f.Abs(rel)); err == nil && fi.Mode().IsRegular() && fi.Size() == size {
			return true
		}
	}
	return false
}
