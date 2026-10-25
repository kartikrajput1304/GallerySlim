//go:build !linux && !darwin && !windows

package osutil

// FreeBytes is not implemented on this system.
func FreeBytes(string) (uint64, error) { return 0, ErrUnsupported }

// SyncDir is not implemented on this system.
func SyncDir(string) error { return nil }

// IsTransient reports errors worth retrying a rename for.
func IsTransient(error) bool { return false }
