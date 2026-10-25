package store

import "testing"

func TestPreviewable(t *testing.T) {
	for name, want := range map[string]bool{"a.JPG": true, "b.jpeg": true, "c.png": true, "d.heic": false, "e.mp4": false, "f": false} {
		if Previewable(name) != want {
			t.Errorf("%s", name)
		}
	}
}
