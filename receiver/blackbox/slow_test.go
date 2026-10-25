package blackbox

// Keep-awake and idle auto-quit: PDF 10.7 (page 82). These take more than 30 minutes and run
// only with GS_BB_SLOW=1, e.g.
//
//	GS_BB_SLOW=1 go test ./blackbox/ -run 'TestRK0' -timeout 45m

import (
	"os"
	"strings"
	"syscall"
	"testing"
	"time"
)

func needSlow(t *testing.T) {
	if os.Getenv("GS_BB_SLOW") == "" {
		t.Skip("takes over 30 minutes; set GS_BB_SLOW=1")
	}
}

// RK-02: page mode, page not open (not polled) and nothing received → the receiver stops after
// 30–31 minutes with "The page has been closed for a while; stopping." (exit 0).
func TestRK02_IdleQuit(t *testing.T) {
	needSlow(t)
	t.Parallel()
	h := newHome(t)
	start := time.Now()
	pr := startReceiver(t, h.env(true), "--no-browser")
	pr.waitFor(t, "Close it or press Ctrl+C to stop.", 15*time.Second)
	code := pr.wait(t, 34*time.Minute)
	el := time.Since(start)
	if !strings.Contains(pr.output(), "The page has been closed for a while; stopping.") {
		t.Errorf("no idle message:\n%s", pr.output())
	}
	if code != 0 {
		t.Errorf("exit %d, want 0", code)
	}
	if el < 30*time.Minute || el > 31*time.Minute+30*time.Second {
		t.Errorf("stopped after %v, want 30–31 min", el)
	}
}

// RK-03: while the page keeps polling, the receiver does not quit.
func TestRK03_PollingKeepsItRunning(t *testing.T) {
	needSlow(t)
	t.Parallel()
	pg := startPage(t, newHome(t), nil)
	deadline := time.Now().Add(33 * time.Minute)
	for time.Now().Before(deadline) {
		select {
		case <-pg.p.done:
			t.Fatalf("receiver quit although the page was polling:\n%s", pg.p.output())
		case <-time.After(10 * time.Second):
		}
		pg.status(t)
	}
	pg.p.cmd.Process.Signal(syscall.SIGINT)
}
