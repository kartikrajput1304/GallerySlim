//go:build !windows && !linux && !darwin && !freebsd && !netbsd && !openbsd && !dragonfly

package desktop

// LockInstance has no lock to take here; every copy runs.
func LockInstance(dir string) (unlock func(), ok bool, err error) { return noRelease, true, nil }
