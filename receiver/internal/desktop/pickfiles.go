package desktop

import (
	"path/filepath"
	"strings"
)

// defaultFilesTitle is used when PickFiles gets an empty title.
const defaultFilesTitle = "Choose photos and videos to send to your phone"

// maxPicked bounds how many files one PickFiles call returns.
const maxPicked = 5000

// appleFilesScript returns the lines of an AppleScript that lets the user choose several
// files and returns their POSIX paths one per line, or "" when cancelled.
func appleFilesScript(title string) []string {
	return []string{
		"try",
		"activate",
		"end try",
		"try",
		"set chosen to choose file with prompt " + appleScriptString(title) + " with multiple selections allowed",
		"on error number -128",
		`return ""`,
		"end try",
		`set out to ""`,
		"repeat with f in chosen",
		"set out to out & (POSIX path of f) & linefeed",
		"end repeat",
		"return out",
	}
}

// splitPicked turns a dialog's newline-separated output into clean absolute paths. Lines
// that aren't absolute paths are dropped.
func splitPicked(out string) []string {
	var paths []string
	for _, line := range strings.Split(out, "\n") {
		line = strings.TrimRight(line, "\r")
		if line == "" || !filepath.IsAbs(line) {
			continue
		}
		paths = append(paths, filepath.Clean(line))
		if len(paths) >= maxPicked {
			break
		}
	}
	return paths
}
