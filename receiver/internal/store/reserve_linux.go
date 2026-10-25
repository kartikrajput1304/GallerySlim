//go:build linux

package store

import (
	"errors"
	"fmt"
	"os"
	"runtime"
	"syscall"
	"unsafe"
)

const (
	renameExchange = 1 << 1 // RENAME_EXCHANGE
	atFDCWD        = -100   // AT_FDCWD
)

func renameat2Nr() uintptr {
	switch runtime.GOARCH {
	case "amd64":
		return 316
	case "arm64", "riscv64", "loong64":
		return 276
	case "386":
		return 353
	case "arm":
		return 382
	}
	return 0
}

func renameat2(from, to string, flags uintptr) error {
	nr := renameat2Nr()
	if nr == 0 {
		return syscall.ENOSYS
	}
	p1, err := syscall.BytePtrFromString(from)
	if err != nil {
		return err
	}
	p2, err := syscall.BytePtrFromString(to)
	if err != nil {
		return err
	}
	cwd := atFDCWD
	_, _, e := syscall.Syscall6(nr, uintptr(cwd), uintptr(unsafe.Pointer(p1)), uintptr(cwd), uintptr(unsafe.Pointer(p2)), flags, 0)
	if e != 0 {
		return e
	}
	return nil
}

func reserveName(path string) (*reservation, error) { return createExclusive(path) }

// moveOnto atomically exchanges the finished part file with the placeholder
// (renameat2 RENAME_EXCHANGE). If what came back is not our placeholder, someone replaced it:
// the exchange is undone, so their file is back where it was, and another name is used.
// File systems without RENAME_EXCHANGE use the portable verify-then-rename path.
func moveOnto(part string, r *reservation) error {
	if testForceFallback {
		return verifyAndRename(part, r)
	}
	err := renameat2(part, r.path, renameExchange)
	if errors.Is(err, syscall.ENOSYS) || errors.Is(err, syscall.EINVAL) || errors.Is(err, syscall.EOPNOTSUPP) {
		return verifyAndRename(part, r)
	}
	if err != nil {
		return &os.LinkError{Op: "renameat2", Old: part, New: r.path, Err: err}
	}
	if stillOurs(part, r.info) {
		// The part path (in our private folder) now holds the empty placeholder.
		os.Remove(part)
		return nil
	}
	if err := renameat2(part, r.path, renameExchange); err != nil {
		return fmt.Errorf("store: could not put back %s: %w", r.path, err)
	}
	return errLostReservation
}

func dropReservation(r *reservation) { dropPlaceholder(r) }
