// Package blackbox holds black-box tests for the GallerySlim Receiver, written from the
// feature reference (docs/features/GallerySlim-Features.pdf, chapter 10 and 8.3) only.
// Case IDs (RH-01, RT-08, ...) refer to docs/testing/blackbox-share-cases.md.
//
// TestMain builds the real receiver binary once. When the system allows it, the whole test
// binary re-runs itself inside a private network namespace (unshare -rn) that only has a
// loopback interface, so tests that let the receiver search for phones can never probe the
// real local network. Tests that need that isolation skip themselves when it is unavailable.
package blackbox

import (
	"bufio"
	"bytes"
	"context"
	"fmt"
	"io"
	"net"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"strings"
	"sync"
	"testing"
	"time"
)

const receiverVersion = "0.1.0"

var (
	receiverBin string // built binary
	inNetns     bool   // running inside a loopback-only network namespace
)

func TestMain(m *testing.M) {
	if os.Getenv("GS_BB_IN_NETNS") == "" && os.Getenv("GS_BB_NO_NETNS") == "" && netnsWorks() {
		cmd := exec.Command("unshare", append([]string{"-rn", "sh", "-c",
			`ip link set lo up && exec "$0" "$@"`, os.Args[0]}, os.Args[1:]...)...)
		cmd.Env = append(os.Environ(), "GS_BB_IN_NETNS=1")
		cmd.Stdin, cmd.Stdout, cmd.Stderr = os.Stdin, os.Stdout, os.Stderr
		err := cmd.Run()
		if ee, ok := err.(*exec.ExitError); ok {
			os.Exit(ee.ExitCode())
		}
		if err != nil {
			fmt.Fprintln(os.Stderr, "netns re-exec failed:", err)
			os.Exit(1)
		}
		os.Exit(0)
	}
	inNetns = os.Getenv("GS_BB_IN_NETNS") == "1"

	dir, err := os.MkdirTemp("", "gs-bb-bin")
	if err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
	receiverBin = filepath.Join(dir, "GallerySlim-Receiver")
	if err := buildReceiver(receiverBin); err != nil {
		fmt.Fprintln(os.Stderr, "building the receiver failed:", err)
		os.RemoveAll(dir)
		os.Exit(1)
	}
	code := m.Run()
	os.RemoveAll(dir)
	os.Exit(code)
}

// netnsWorks reports whether an unprivileged loopback-only network namespace can be made.
func netnsWorks() bool {
	if _, err := exec.LookPath("unshare"); err != nil {
		return false
	}
	if _, err := exec.LookPath("ip"); err != nil {
		return false
	}
	out, err := exec.Command("unshare", "-rn", "sh", "-c", "ip link set lo up && echo ok").CombinedOutput()
	return err == nil && strings.TrimSpace(string(out)) == "ok"
}

func goTool() string {
	if p := filepath.Join(runtime.GOROOT(), "bin", "go"); fileExists(p) {
		return p
	}
	return "go"
}

func buildReceiver(out string) error {
	wd, _ := os.Getwd() // receiver/blackbox
	cmd := exec.Command(goTool(), "build", "-trimpath", "-ldflags", "-X main.version="+receiverVersion, "-o", out, ".")
	cmd.Dir = filepath.Dir(wd)
	cmd.Env = append(os.Environ(), "CGO_ENABLED=0", "GOTOOLCHAIN=local", "GOPROXY=off", "GOFLAGS=-mod=mod")
	b, err := cmd.CombinedOutput()
	if err != nil {
		return fmt.Errorf("%v: %s", err, b)
	}
	return nil
}

func fileExists(p string) bool { _, err := os.Stat(p); return err == nil }

func needNetns(t *testing.T) {
	t.Helper()
	if !inNetns {
		t.Skip("needs a loopback-only network namespace (unshare -rn) so the receiver cannot probe the real network")
	}
}

// home is an isolated user home with its own config folder.
type home struct {
	Dir, Config string
}

func newHome(t *testing.T) home {
	t.Helper()
	d := t.TempDir()
	h := home{Dir: d, Config: filepath.Join(d, ".config")}
	if err := os.MkdirAll(h.Config, 0o700); err != nil {
		t.Fatal(err)
	}
	return h
}

// settingsDir is "GallerySlim Receiver" inside ~/.config (10.9).
func (h home) settingsDir() string { return filepath.Join(h.Config, "GallerySlim Receiver") }

// env returns a minimal environment for the receiver. desktop=false removes DISPLAY and
// WAYLAND_DISPLAY (Linux "no desktop").
func (h home) env(desktop bool, extra ...string) []string {
	e := []string{
		"HOME=" + h.Dir,
		"XDG_CONFIG_HOME=" + h.Config,
		"PATH=/usr/bin:/bin",
		"LANG=C.UTF-8",
	}
	if desktop {
		e = append(e, "DISPLAY=:987")
	}
	return append(e, extra...)
}

// proc is a running receiver with its combined output collected line by line.
type proc struct {
	cmd   *exec.Cmd
	mu    sync.Mutex
	out   bytes.Buffer
	lines []string
	done  chan struct{}
	code  int
}

func startReceiver(t *testing.T, env []string, args ...string) *proc {
	t.Helper()
	cmd := exec.Command(receiverBin, args...)
	cmd.Env = env
	cmd.Dir = t.TempDir() // a relative folder can never land in the source tree
	pr, pw := io.Pipe()
	cmd.Stdout, cmd.Stderr = pw, pw
	p := &proc{cmd: cmd, done: make(chan struct{})}
	if err := cmd.Start(); err != nil {
		t.Fatal(err)
	}
	go func() {
		sc := bufio.NewScanner(pr)
		sc.Buffer(make([]byte, 1<<20), 1<<20)
		for sc.Scan() {
			p.mu.Lock()
			p.lines = append(p.lines, sc.Text())
			p.out.WriteString(sc.Text() + "\n")
			p.mu.Unlock()
		}
	}()
	go func() {
		err := cmd.Wait()
		p.code = 0
		if ee, ok := err.(*exec.ExitError); ok {
			p.code = ee.ExitCode()
		} else if err != nil {
			p.code = -1
		}
		pw.Close()
		close(p.done)
	}()
	t.Cleanup(func() {
		select {
		case <-p.done:
		default:
			_ = cmd.Process.Kill()
			<-p.done
		}
	})
	return p
}

func (p *proc) output() string {
	p.mu.Lock()
	defer p.mu.Unlock()
	return p.out.String()
}

// wait waits for the process to exit and returns its exit code, or fails after d.
func (p *proc) wait(t *testing.T, d time.Duration) int {
	t.Helper()
	select {
	case <-p.done:
		time.Sleep(50 * time.Millisecond) // let the reader drain
		return p.code
	case <-time.After(d):
		_ = p.cmd.Process.Kill()
		<-p.done
		t.Fatalf("receiver did not exit within %v; output:\n%s", d, p.output())
		return -1
	}
}

// waitFor waits until the output contains s.
func (p *proc) waitFor(t *testing.T, s string, d time.Duration) {
	t.Helper()
	deadline := time.Now().Add(d)
	for time.Now().Before(deadline) {
		if strings.Contains(p.output(), s) {
			return
		}
		select {
		case <-p.done:
			if strings.Contains(p.output(), s) {
				return
			}
			t.Fatalf("receiver exited (code %d) before printing %q; output:\n%s", p.code, s, p.output())
		case <-time.After(50 * time.Millisecond):
		}
	}
	t.Fatalf("receiver did not print %q within %v; output:\n%s", s, d, p.output())
}

// runReceiver runs the binary to completion and returns exit code and combined output.
func runReceiver(t *testing.T, env []string, d time.Duration, args ...string) (int, string) {
	t.Helper()
	p := startReceiver(t, env, args...)
	code := p.wait(t, d)
	return code, p.output()
}

// closedPort returns a 127.0.0.1 port that nothing listens on.
func closedPort(t *testing.T) int {
	t.Helper()
	l, err := net.Listen("tcp4", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	port := l.Addr().(*net.TCPAddr).Port
	l.Close()
	return port
}

func ctxFor(t *testing.T, d time.Duration) context.Context {
	ctx, cancel := context.WithTimeout(context.Background(), d)
	t.Cleanup(cancel)
	return ctx
}
