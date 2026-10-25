// Package applog writes the receiver's log file. Callers must never pass secrets: not K, not
// the QR text, not keys, MACs or counters with their MACs, and not the local page token.
package applog

import (
	"io"
	"log/slog"
	"os"
	"path/filepath"
	"sync"
)

// FileName is the log file inside the config folder.
const FileName = "receiver.log"

const maxSize = 2 << 20

type rotatingFile struct {
	mu   sync.Mutex
	path string
	f    *os.File
	size int64
}

func (r *rotatingFile) Write(p []byte) (int, error) {
	r.mu.Lock()
	defer r.mu.Unlock()
	if r.f == nil {
		return len(p), nil
	}
	if r.size+int64(len(p)) > maxSize {
		r.f.Close()
		os.Rename(r.path, r.path+".1") // keep one old log
		f, err := os.OpenFile(r.path, os.O_CREATE|os.O_WRONLY|os.O_TRUNC, 0o600)
		if err != nil {
			r.f = nil
			return len(p), nil
		}
		r.f, r.size = f, 0
	}
	n, err := r.f.Write(p)
	r.size += int64(n)
	return n, err
}

// Open returns a logger writing to <dir>/receiver.log (a discarding logger if that fails).
func Open(dir string) *slog.Logger {
	if dir == "" {
		return Discard()
	}
	path := filepath.Join(dir, FileName)
	f, err := os.OpenFile(path, os.O_CREATE|os.O_WRONLY|os.O_APPEND, 0o600)
	if err != nil {
		return Discard()
	}
	fi, _ := f.Stat()
	var size int64
	if fi != nil {
		size = fi.Size()
	}
	return slog.New(slog.NewTextHandler(&rotatingFile{path: path, f: f, size: size}, nil))
}

// Discard returns a logger that writes nothing.
func Discard() *slog.Logger { return slog.New(slog.NewTextHandler(io.Discard, nil)) }
