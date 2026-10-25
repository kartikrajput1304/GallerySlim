package store

import (
	"path/filepath"
	"strings"
)

// Previewable reports whether a file name is a JPEG or PNG, the formats the page can make a
// small picture of (ui.ThumbFromFile).
func Previewable(name string) bool {
	switch strings.ToLower(filepath.Ext(name)) {
	case ".jpg", ".jpeg", ".png":
		return true
	}
	return false
}
