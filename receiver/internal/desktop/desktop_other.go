//go:build !unix && !windows

// Systems with no desktop to talk to (Plan 9, js/wasm, wasip1): only the pure
// parts work.

package desktop

import (
	"os"
	"path/filepath"
)

// PickFolder is not available here.
func PickFolder(title, start string) (string, error) { return "", ErrUnsupported }

// DefaultFolder suggests <home>/Pictures.
func DefaultFolder() (path, note string) {
	home, _ := os.UserHomeDir()
	pictures := ""
	if home != "" {
		pictures = filepath.Join(home, "Pictures")
	}
	return chooseDefault(styleUnix, pictures, home, CloudSynced)
}

// CloudSynced checks path by folder names only.
func CloudSynced(path string) (synced bool, provider string) {
	home, _ := os.UserHomeDir()
	return cloudSyncedResolved(styleUnix, path, home, os.Getenv)
}

// OpenURL is not available here.
func OpenURL(link string) error { return ErrUnsupported }

// OpenFolder is not available here.
func OpenFolder(path string) error { return ErrUnsupported }

// KeepAwake does nothing here; release is never nil.
func KeepAwake() (release func()) { return noRelease }
