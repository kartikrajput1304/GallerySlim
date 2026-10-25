//go:build !linux && !windows

package store

func reserveName(path string) (*reservation, error) { return createExclusive(path) }

// moveOnto checks the placeholder is still ours, then renames onto it (see verifyAndRename).
func moveOnto(part string, r *reservation) error { return verifyAndRename(part, r) }

func dropReservation(r *reservation) { dropPlaceholder(r) }
