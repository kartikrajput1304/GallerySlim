//go:build windows

package store

import (
	"errors"
	"io/fs"
	"os"
	"path/filepath"
	"strings"
	"syscall"
	"time"
	"unsafe"

	"galleryslim/receiver/internal/osutil"
)

var (
	modkernel32                    = syscall.NewLazyDLL("kernel32.dll")
	procSetFileInformationByHandle = modkernel32.NewProc("SetFileInformationByHandle")
	errorFileExists                = syscall.Errno(80)
	errorAlreadyExists             = syscall.Errno(183)
)

const (
	accessDelete        = 0x00010000 // DELETE
	fileDispositionInfo = 4          // FileDispositionInfo
)

// longPath adds the \\?\ prefix Win32 needs for paths of 248+ characters.
func longPath(p string) string {
	if len(p) < 248 || strings.HasPrefix(p, `\\?\`) || !filepath.IsAbs(p) {
		return p
	}
	if strings.HasPrefix(p, `\\`) {
		return `\\?\UNC\` + p[2:]
	}
	return `\\?\` + p
}

// reserveName creates the placeholder with CREATE_NEW (the Win32 form of O_CREATE|O_EXCL)
// and keeps it open without sharing, so no other program can open, replace or delete it
// until we let go.
func reserveName(path string) (*reservation, error) {
	p, err := syscall.UTF16PtrFromString(longPath(path))
	if err != nil {
		return nil, err
	}
	h, err := syscall.CreateFile(p, syscall.GENERIC_WRITE|accessDelete, 0, nil,
		syscall.CREATE_NEW, syscall.FILE_ATTRIBUTE_NORMAL, 0)
	if err != nil {
		if errors.Is(err, errorFileExists) || errors.Is(err, errorAlreadyExists) {
			return nil, &fs.PathError{Op: "create", Path: path, Err: fs.ErrExist}
		}
		return nil, &fs.PathError{Op: "create", Path: path, Err: err}
	}
	return &reservation{path: path, handle: uintptr(h)}, nil
}

// deleteViaHandle marks exactly our placeholder for deletion and closes it.
func deleteViaHandle(r *reservation) error {
	del := byte(1) // FILE_DISPOSITION_INFO{DeleteFile: TRUE}
	r1, _, e := procSetFileInformationByHandle.Call(r.handle, fileDispositionInfo, uintptr(unsafe.Pointer(&del)), 1)
	syscall.CloseHandle(syscall.Handle(r.handle))
	r.handle = 0
	if r1 == 0 {
		return e
	}
	return nil
}

// moveOnto deletes our placeholder through its own handle (so only that file can be removed),
// then moves the part file with MoveFileW, which fails rather than replace a file that
// appeared in between. Transient sharing violations are retried.
func moveOnto(part string, r *reservation) error {
	if err := deleteViaHandle(r); err != nil {
		// Could not mark it; fall back to checking it is still an empty file and replacing it.
		fi, serr := os.Lstat(r.path)
		if serr != nil || !fi.Mode().IsRegular() || fi.Size() != 0 {
			return errLostReservation
		}
		return renameRetryReplace(part, r.path)
	}
	from, err := syscall.UTF16PtrFromString(longPath(part))
	if err != nil {
		return err
	}
	to, err := syscall.UTF16PtrFromString(longPath(r.path))
	if err != nil {
		return err
	}
	for i := 0; ; i++ {
		err = syscall.MoveFile(from, to)
		if err == nil {
			return nil
		}
		if errors.Is(err, errorFileExists) || errors.Is(err, errorAlreadyExists) {
			return errLostReservation
		}
		if !osutil.IsTransient(err) || i >= 5 {
			return &os.LinkError{Op: "MoveFile", Old: part, New: r.path, Err: err}
		}
		time.Sleep(time.Duration(100<<i) * time.Millisecond)
	}
}

func renameRetryReplace(from, to string) error { return renameRetry(from, to) }

func dropReservation(r *reservation) {
	if r.handle != 0 {
		deleteViaHandle(r)
	}
}
