//go:build windows

package osutil

import (
	"errors"
	"syscall"
	"unsafe"
)

var (
	modkernel32             = syscall.NewLazyDLL("kernel32.dll")
	procGetDiskFreeSpaceExW = modkernel32.NewProc("GetDiskFreeSpaceExW")
)

// FreeBytes returns the bytes available to this user on the volume holding path.
func FreeBytes(path string) (uint64, error) {
	p, err := syscall.UTF16PtrFromString(path)
	if err != nil {
		return 0, err
	}
	var avail, total, free uint64
	r, _, e := procGetDiskFreeSpaceExW.Call(uintptr(unsafe.Pointer(p)),
		uintptr(unsafe.Pointer(&avail)), uintptr(unsafe.Pointer(&total)), uintptr(unsafe.Pointer(&free)))
	if r == 0 {
		return 0, e
	}
	return avail, nil
}

// SyncDir is a no-op on Windows: directories cannot be flushed through a normal handle, and
// NTFS journals the rename itself.
func SyncDir(string) error { return nil }

const (
	errorAccessDenied     = syscall.Errno(5)
	errorSharingViolation = syscall.Errno(32)
	errorLockViolation    = syscall.Errno(33)
)

// IsTransient reports errors that antivirus scanners, OneDrive or the search indexer cause
// by briefly holding a file open; a rename is retried a few times for these.
func IsTransient(err error) bool {
	var en syscall.Errno
	if !errors.As(err, &en) {
		return false
	}
	return en == errorAccessDenied || en == errorSharingViolation || en == errorLockViolation
}
