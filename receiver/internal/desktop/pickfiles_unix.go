//go:build unix && !darwin

package desktop

import (
	"bytes"
	"errors"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
)

// PickFiles shows the desktop's multiple-file dialog (zenity or kdialog). It returns nil and
// nil if the user cancelled.
func PickFiles(title string) ([]string, error) {
	if os.Getenv("DISPLAY") == "" && os.Getenv("WAYLAND_DISPLAY") == "" {
		return nil, ErrUnsupported
	}
	if title == "" {
		title = defaultFilesTitle
	}
	for _, tool := range dialogOrder() {
		bin, err := exec.LookPath(tool)
		if err != nil {
			continue
		}
		var args []string
		if tool == "kdialog" {
			start := "/"
			if home, err := os.UserHomeDir(); err == nil && filepath.IsAbs(home) {
				start = home
			}
			args = []string{"--getopenfilename", start, "--multiple", "--separate-output", "--title", title}
		} else {
			args = []string{"--file-selection", "--multiple", "--separator=\n", "--title=" + title}
		}
		cmd := exec.Command(bin, args...)
		var stdout, stderr bytes.Buffer
		cmd.Stdout, cmd.Stderr = &stdout, &stderr
		if err := cmd.Run(); err != nil {
			var ee *exec.ExitError
			if errors.As(err, &ee) && ee.ExitCode() == 1 {
				return nil, nil
			}
			msg, _, _ := strings.Cut(strings.TrimSpace(stderr.String()), "\n")
			return nil, fmt.Errorf("%s: %w %s", filepath.Base(bin), err, msg)
		}
		return splitPicked(stdout.String()), nil
	}
	return nil, ErrUnsupported
}
