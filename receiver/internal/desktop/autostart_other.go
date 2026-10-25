//go:build !unix && !windows

package desktop

// SetStartAtLogin is not available here.
func SetStartAtLogin(enabled bool) error { return ErrUnsupported }

// StartAtLogin is always false here.
func StartAtLogin() bool { return false }

// InstallSupported is false here.
func InstallSupported() bool { return false }

// Installed is always false here.
func Installed() bool { return false }

// InstallSelf is not available here.
func InstallSelf() (string, error) { return "", ErrUnsupported }
