package client

import (
	"io"
	"sync/atomic"
	"time"
)

// idleReader cancels its request when no bytes arrive for the idle duration, so a dead Wi-Fi
// link turns into an error (and a resume) instead of a hang.
type idleReader struct {
	rc      io.ReadCloser
	idle    time.Duration
	timer   *time.Timer
	stalled atomic.Bool
}

func newIdleReader(rc io.ReadCloser, idle time.Duration, cancel func()) *idleReader {
	r := &idleReader{rc: rc, idle: idle}
	r.timer = time.AfterFunc(idle, func() {
		r.stalled.Store(true)
		cancel()
	})
	return r
}

func (r *idleReader) Read(p []byte) (int, error) {
	n, err := r.rc.Read(p)
	if n > 0 {
		r.timer.Reset(r.idle)
	}
	return n, err
}

func (r *idleReader) Close() error {
	r.timer.Stop()
	return r.rc.Close()
}
