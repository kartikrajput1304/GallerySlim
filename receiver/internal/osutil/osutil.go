// Package osutil holds the small per-OS file system helpers the receiver needs.
package osutil

import "errors"

// ErrUnsupported is returned where an operation has no implementation on this OS.
var ErrUnsupported = errors.New("not supported on this system")
