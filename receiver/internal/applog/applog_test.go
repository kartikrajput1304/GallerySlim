package applog

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestLogFileAndRotation(t *testing.T) {
	dir := t.TempDir()
	log := Open(dir)
	log.Info("hello", "n", 1)
	b, err := os.ReadFile(filepath.Join(dir, FileName))
	if err != nil || !strings.Contains(string(b), "msg=hello") {
		t.Fatalf("%v %q", err, b)
	}
	big := strings.Repeat("x", 1<<20)
	for i := 0; i < 3; i++ {
		log.Info(big)
	}
	if fi, err := os.Stat(filepath.Join(dir, FileName)); err != nil || fi.Size() > maxSize {
		t.Errorf("log not rotated: %v", err)
	}
	if _, err := os.Stat(filepath.Join(dir, FileName+".1")); err != nil {
		t.Error("no rotated log kept")
	}
	if Open("") == nil || Open(filepath.Join(dir, "missing", "dir")) == nil {
		t.Error("fallback logger is nil")
	}
}
