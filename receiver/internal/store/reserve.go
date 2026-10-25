package store

import (
	"errors"
	"os"
	"time"

	"galleryslim/receiver/internal/osutil"
)

// errLostReservation means our placeholder was replaced or taken by someone else before the
// move; the caller picks the next name and leaves that path alone.
var errLostReservation = errors.New("store: reserved name was taken by another program")

// reservation is an empty placeholder created with create-exclusive semantics.
type reservation struct {
	path   string
	info   os.FileInfo // identity of the placeholder (Unix)
	handle uintptr     // open exclusive handle of the placeholder (Windows)
}

// pinIdentity makes sure a FileInfo's file identity is loaded now (on Windows it is loaded
// lazily from the path, which would be gone after a rename).
func pinIdentity(fi os.FileInfo) { os.SameFile(fi, fi) }

// stillOurs reports whether path is still the empty placeholder described by info.
func stillOurs(path string, info os.FileInfo) bool {
	fi, err := os.Lstat(path)
	return err == nil && fi.Mode().IsRegular() && fi.Size() == 0 && os.SameFile(fi, info)
}

// createExclusive creates an empty placeholder with O_CREATE|O_EXCL.
func createExclusive(path string) (*reservation, error) {
	f, err := os.OpenFile(path, os.O_CREATE|os.O_EXCL|os.O_WRONLY, 0o644)
	if err != nil {
		return nil, err
	}
	fi, err := f.Stat()
	f.Close()
	if err != nil {
		os.Remove(path)
		return nil, err
	}
	pinIdentity(fi)
	return &reservation{path: path, info: fi}, nil
}

// renameRetry renames, retrying errors that a virus scanner or sync client cause by briefly
// holding a file open (Windows sharing violations).
func renameRetry(from, to string) error {
	var err error
	for i := 0; i < 6; i++ {
		if err = os.Rename(from, to); err == nil || !osutil.IsTransient(err) {
			return err
		}
		time.Sleep(time.Duration(100<<i) * time.Millisecond)
	}
	return err
}

// verifyAndRename is the portable fallback: the placeholder must still be ours, then the
// rename replaces it. A program replacing the placeholder in the microseconds between the
// check and the rename is not detected.
func verifyAndRename(part string, r *reservation) error {
	if !stillOurs(r.path, r.info) {
		return errLostReservation
	}
	return renameRetry(part, r.path)
}

// dropPlaceholder removes the placeholder after a failed save, only if it is still ours.
func dropPlaceholder(r *reservation) {
	if r.info != nil && stillOurs(r.path, r.info) {
		os.Remove(r.path)
	}
}

// testBeforeMove, when set by tests, runs between reserving a name and moving the file in.
var testBeforeMove func(path string)

// testForceFallback makes tests use the portable verify-then-rename path.
var testForceFallback bool
