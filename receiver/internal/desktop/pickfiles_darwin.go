//go:build darwin

package desktop

import (
	"bytes"
	"fmt"
	"os/exec"
	"strings"
)

// PickFiles shows the macOS "choose file" dialog with multiple selection. It returns nil and
// nil if the user cancelled.
func PickFiles(title string) ([]string, error) {
	osa, err := toolPath("/usr/bin/osascript")
	if err != nil {
		return nil, ErrUnsupported
	}
	if title == "" {
		title = defaultFilesTitle
	}
	var args []string
	for _, line := range appleFilesScript(title) {
		args = append(args, "-e", line)
	}
	cmd := exec.Command(osa, args...)
	var stdout, stderr bytes.Buffer
	cmd.Stdout, cmd.Stderr = &stdout, &stderr
	if err := cmd.Run(); err != nil {
		msg := strings.TrimSpace(stderr.String())
		if strings.Contains(msg, "(-128)") {
			return nil, nil
		}
		return nil, fmt.Errorf("osascript: %w: %s", err, msg)
	}
	return splitPicked(stdout.String()), nil
}
