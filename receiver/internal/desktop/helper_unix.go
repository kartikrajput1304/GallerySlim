//go:build unix

package desktop

import (
	"fmt"
	"io"
	"os/exec"
	"path/filepath"
	"time"
)

const (
	// helperGrace is how long release waits for a stay-awake helper to exit.
	helperGrace = 2 * time.Second
	// openerWait is how long OpenURL/OpenFolder wait for the opener to report
	// failure. Some xdg-open fallbacks run the browser in the foreground and
	// never return; after this long the opener is left running and counted as
	// a success.
	openerWait = 5 * time.Second
)

// startHelper starts cmd as a background child that lives until the returned
// release is called. With viaStdin the child is given a pipe as stdin, and
// release closes it first so a child that waits for end-of-input (cat) exits
// cleanly; the pipe also closes if this process dies, even from SIGKILL. A
// child still running after helperGrace is killed. release is idempotent and
// safe to call from several goroutines.
func startHelper(cmd *exec.Cmd, viaStdin bool) (func(), error) {
	var stdin io.WriteCloser
	if viaStdin {
		w, err := cmd.StdinPipe()
		if err != nil {
			return nil, err
		}
		stdin = w
	}
	if err := cmd.Start(); err != nil {
		return nil, err
	}
	done := make(chan struct{})
	go func() {
		_ = cmd.Wait()
		close(done)
	}()
	return onceRelease(func() {
		if stdin != nil {
			_ = stdin.Close()
			select {
			case <-done:
				return
			case <-time.After(helperGrace):
			}
		}
		_ = cmd.Process.Kill()
		select {
		case <-done:
		case <-time.After(helperGrace):
		}
	}), nil
}

// runOpener runs a program that hands a link or folder to the desktop (open,
// xdg-open) and reports its failure if it fails within openerWait.
func runOpener(bin string, args ...string) error {
	cmd := exec.Command(bin, args...)
	if err := cmd.Start(); err != nil {
		return err
	}
	done := make(chan error, 1)
	go func() { done <- cmd.Wait() }()
	select {
	case err := <-done:
		if err != nil {
			return fmt.Errorf("%s: %w", filepath.Base(bin), err)
		}
		return nil
	case <-time.After(openerWait):
		return nil
	}
}
