//go:build linux || darwin || freebsd || netbsd || openbsd || dragonfly

package desktop

import (
	"errors"
	"os"
	"path/filepath"
	"syscall"
)

// LockInstance takes the per-user single-instance lock in dir. ok is false when another
// receiver holds it. The lock goes away with the process, even after a crash.
func LockInstance(dir string) (unlock func(), ok bool, err error) {
	f, err := os.OpenFile(filepath.Join(dir, "instance.lock"), os.O_CREATE|os.O_RDWR, 0o600)
	if err != nil {
		return noRelease, false, err
	}
	if err := syscall.Flock(int(f.Fd()), syscall.LOCK_EX|syscall.LOCK_NB); err != nil {
		f.Close()
		if errors.Is(err, syscall.EWOULDBLOCK) {
			return noRelease, false, nil
		}
		return noRelease, false, err
	}
	return onceRelease(func() { f.Close() }), true, nil
}
