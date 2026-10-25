package desktop

import (
	"strings"
	"testing"
)

func TestSplitPicked(t *testing.T) {
	got := splitPicked("/home/k/a.jpg\n/home/k/b c.mov\r\nrelative.jpg\n\n/home/k/../k/d.png\n")
	want := []string{"/home/k/a.jpg", "/home/k/b c.mov", "/home/k/d.png"}
	if strings.Join(got, "|") != strings.Join(want, "|") {
		t.Errorf("%q", got)
	}
}

func TestAppleFilesScript(t *testing.T) {
	s := strings.Join(appleFilesScript(`Pick "these"`+"\n"), "\n")
	if !strings.Contains(s, `choose file with prompt "Pick \"these\"\n" with multiple selections allowed`) {
		t.Errorf("%s", s)
	}
}
