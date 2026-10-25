//go:build linux || darwin

package osutil

import (
	"os"
	"syscall"
)

// FreeBytes returns the bytes available to this user on the volume holding path.
func FreeBytes(path string) (uint64, error) {
	var st syscall.Statfs_t
	if err := syscall.Statfs(path, &st); err != nil {
		return 0, &os.PathError{Op: "statfs", Path: path, Err: err}
	}
	return uint64(st.Bavail) * uint64(st.Bsize), nil
}

// SyncDir flushes a directory's entries (new names, renames) to disk.
func SyncDir(dir string) error {
	d, err := os.Open(dir)
	if err != nil {
		return err
	}
	defer d.Close()
	return d.Sync()
}

// IsTransient reports errors worth retrying a rename for. On Unix there are none.
func IsTransient(error) bool { return false }
