// Package store saves received files into the folder the user chose: month folders directly
// inside it, never overwriting anything, with resumable .part files and a manifest of what
// was saved.
package store

import (
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"fmt"
	"io"
	"io/fs"
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"sync"
	"time"

	"galleryslim/receiver/internal/osutil"
)

const (
	// MetaDir is the receiver's own hidden folder inside the chosen folder.
	MetaDir      = ".gallery-slim"
	partsDirName = "parts"
	maxSuffix    = 9999
)

// CaseInsensitiveOS is true where the usual file systems ignore letter case.
var CaseInsensitiveOS = runtime.GOOS == "windows" || runtime.GOOS == "darwin"

var folderLocks sync.Map // lock key → *sync.Mutex

func lockFor(root string) *sync.Mutex {
	key := filepath.Clean(root)
	if CaseInsensitiveOS {
		key = strings.ToLower(key)
	}
	m, _ := folderLocks.LoadOrStore(key, &sync.Mutex{})
	return m.(*sync.Mutex)
}

// Folder is the destination folder.
type Folder struct {
	Root string
	// CaseInsensitive makes name clash checks ignore letter case (default: true on Windows and
	// macOS). Tests may change it before saving.
	CaseInsensitive bool

	mu       *sync.Mutex // shared by every Folder value for the same path
	man      manifest
	manMu    sync.Mutex
	dirty    bool
	lastSave time.Time
}

// Open prepares root (creating it and the receiver's hidden folder if needed) and loads the
// manifest.
func Open(root string) (*Folder, error) {
	if !filepath.IsAbs(root) {
		return nil, fmt.Errorf("store: folder must be an absolute path: %q", root)
	}
	root = filepath.Clean(root)
	if err := os.MkdirAll(filepath.Join(root, MetaDir, partsDirName), 0o755); err != nil {
		return nil, err
	}
	f := &Folder{Root: root, CaseInsensitive: CaseInsensitiveOS, mu: lockFor(root)}
	f.loadManifest()
	return f, nil
}

// CheckWritable creates root if needed and proves a file can be written there.
func CheckWritable(root string) error {
	if !filepath.IsAbs(root) {
		return errors.New("please give a full folder path")
	}
	if err := os.MkdirAll(filepath.Join(root, MetaDir), 0o755); err != nil {
		return err
	}
	f, err := os.CreateTemp(filepath.Join(root, MetaDir), "write-test-*")
	if err != nil {
		return err
	}
	name := f.Name()
	_, werr := f.Write([]byte("ok"))
	cerr := f.Close()
	os.Remove(name)
	if werr != nil {
		return werr
	}
	return cerr
}

func (f *Folder) partsDir() string { return filepath.Join(f.Root, MetaDir, partsDirName) }

// PartPath is where the partial download with the given PartName lives. Anything that is not
// a name made by PartName is refused (so "..", separators etc. can never escape the folder).
func (f *Folder) PartPath(partName string) (string, error) {
	if !partNameRe.MatchString(partName) {
		return "", fmt.Errorf("store: bad part file name %q", partName)
	}
	return filepath.Join(f.partsDir(), partName), nil
}

// FreeBytes is the space available on the folder's volume.
func (f *Folder) FreeBytes() (uint64, error) { return osutil.FreeBytes(f.Root) }

// CleanupParts removes partial downloads untouched for longer than maxAge.
func (f *Folder) CleanupParts(maxAge time.Duration) {
	entries, err := os.ReadDir(f.partsDir())
	if err != nil {
		return
	}
	cutoff := time.Now().Add(-maxAge)
	for _, e := range entries {
		if !strings.HasSuffix(e.Name(), ".part") || !e.Type().IsRegular() {
			continue
		}
		if info, err := e.Info(); err == nil && info.ModTime().Before(cutoff) {
			os.Remove(filepath.Join(f.partsDir(), e.Name()))
		}
	}
}

func (f *Folder) ensureDir(dir string) error {
	if fi, err := os.Stat(dir); err == nil {
		if !fi.IsDir() {
			return fmt.Errorf("store: %s exists and is not a folder", dir)
		}
		return nil
	}
	if err := os.MkdirAll(dir, 0o755); err != nil {
		return err
	}
	_ = osutil.SyncDir(filepath.Dir(dir))
	return nil
}

func nameTakenFold(dir, name string) bool {
	entries, err := os.ReadDir(dir)
	if err != nil {
		return false
	}
	for _, e := range entries {
		if strings.EqualFold(e.Name(), name) {
			return true
		}
	}
	return false
}

// Finalize moves a complete, flushed part file into <root>/<month>/<name>, choosing
// "name (2).ext" and so on when the name is taken (ignoring case where the OS does). It never
// replaces an existing file. mtime, when not zero, becomes the file's modification time.
// It returns the path relative to Root, with forward slashes.
func (f *Folder) Finalize(partPath, month, name string, mtime time.Time) (string, error) {
	month = MonthDir(month)
	if name == "" || CleanName(name, "") != name {
		return "", fmt.Errorf("store: refusing unclean name %q", name)
	}
	if !mtime.IsZero() {
		_ = os.Chtimes(partPath, time.Time{}, mtime)
	}
	partInfo, err := os.Lstat(partPath)
	if err != nil {
		return "", err
	}
	if !partInfo.Mode().IsRegular() {
		return "", fmt.Errorf("store: %s is not a regular file", partPath)
	}
	pinIdentity(partInfo)

	f.mu.Lock()
	defer f.mu.Unlock()
	dir := filepath.Join(f.Root, month)
	if err := f.ensureDir(dir); err != nil {
		return "", err
	}
	for n := 1; n <= maxSuffix; n++ {
		cand := WithSuffix(name, n)
		if f.CaseInsensitive && nameTakenFold(dir, cand) {
			continue
		}
		r, err := reserveName(filepath.Join(dir, cand))
		if errors.Is(err, fs.ErrExist) {
			continue
		}
		if err != nil {
			return "", err
		}
		if testBeforeMove != nil {
			testBeforeMove(r.path)
		}
		err = moveOnto(partPath, r)
		if errors.Is(err, errLostReservation) {
			continue
		}
		if err != nil {
			dropReservation(r)
			return "", err
		}
		fi, err := os.Lstat(r.path)
		if err != nil || !os.SameFile(fi, partInfo) {
			return "", fmt.Errorf("store: %s changed while it was being saved", r.path)
		}
		_ = osutil.SyncDir(dir)
		_ = osutil.SyncDir(f.partsDir())
		return month + "/" + cand, nil
	}
	return "", errors.New("store: no free file name")
}

// Abs turns a relative path returned by Finalize into a full path.
func (f *Folder) Abs(rel string) string {
	return filepath.Join(f.Root, filepath.FromSlash(rel))
}

// HashFile reads a file from disk and returns its SHA-256 (hex) and length.
func HashFile(path string) (string, int64, error) {
	fh, err := os.Open(path)
	if err != nil {
		return "", 0, err
	}
	defer fh.Close()
	h := sha256.New()
	n, err := io.CopyBuffer(h, fh, make([]byte, 1<<20))
	if err != nil {
		return "", n, err
	}
	return hex.EncodeToString(h.Sum(nil)), n, nil
}
