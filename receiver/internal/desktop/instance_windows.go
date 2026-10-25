//go:build windows

package desktop

import (
	"errors"
	"path/filepath"
	"syscall"
)

const (
	errorSharingViolation = 32
	fileFlagDeleteOnClose = 0x04000000
)

// LockInstance opens dir\instance.lock without sharing; a second receiver can't open it
// (ok false). Windows closes the handle, and so releases the lock, when the process ends.
func LockInstance(dir string) (unlock func(), ok bool, err error) {
	p, err := syscall.UTF16PtrFromString(filepath.Join(dir, "instance.lock"))
	if err != nil {
		return noRelease, false, err
	}
	h, err := syscall.CreateFile(p, syscall.GENERIC_READ|syscall.GENERIC_WRITE, 0, nil,
		syscall.OPEN_ALWAYS, syscall.FILE_ATTRIBUTE_NORMAL|fileFlagDeleteOnClose, 0)
	if err != nil {
		if errors.Is(err, syscall.Errno(errorSharingViolation)) {
			return noRelease, false, nil
		}
		return noRelease, false, err
	}
	return onceRelease(func() { syscall.CloseHandle(h) }), true, nil
}
