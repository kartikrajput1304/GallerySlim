package ui

import (
	"bytes"
	"encoding/binary"
	"image"
	"image/color"
	"image/jpeg"
	"image/png"
	"net/http"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"sync/atomic"
	"testing"
)

func encodeJPEG(t *testing.T, w, h int) []byte {
	t.Helper()
	img := image.NewRGBA(image.Rect(0, 0, w, h))
	for y := 0; y < h; y++ {
		for x := 0; x < w; x++ {
			img.Set(x, y, color.RGBA{uint8(x), uint8(y), 128, 255})
		}
	}
	var b bytes.Buffer
	if err := jpeg.Encode(&b, img, nil); err != nil {
		t.Fatal(err)
	}
	return b.Bytes()
}

func encodePNG(t *testing.T, img image.Image) []byte {
	t.Helper()
	var b bytes.Buffer
	if err := png.Encode(&b, img); err != nil {
		t.Fatal(err)
	}
	return b.Bytes()
}

type thumbReply struct {
	data []byte
	mime string
	ok   bool
}

func TestThumbRoute(t *testing.T) {
	small := encodeJPEG(t, 64, 48)
	var reply atomic.Value
	reply.Store(thumbReply{small, "image/jpeg", true})
	var calls atomic.Int32
	var asked atomic.Int32
	c := startUI(t, Handlers{
		Snapshot: func() Snapshot {
			return Snapshot{Phase: "receiving", Rows: []FileRow{
				{Name: "a.jpg", State: "verified", Thumb: true},
				{Name: "b.heic", State: "verified"},
				{Name: "c.jpg", State: "saved", Thumb: true},
			}}
		},
		Thumb: func(i int) ([]byte, string, bool) {
			calls.Add(1)
			asked.Store(int32(i))
			r := reply.Load().(thumbReply)
			if r.mime == "panic" {
				panic("secret")
			}
			return r.data, r.mime, r.ok
		},
	})

	resp, body := c.do(http.MethodGet, "thumb/2", "", nil)
	if resp.StatusCode != 200 || body != string(small) || asked.Load() != 2 {
		t.Fatalf("thumb/2 = %d, %d bytes, asked %d", resp.StatusCode, len(body), asked.Load())
	}
	h := resp.Header
	if h.Get("Content-Type") != "image/jpeg" || h.Get("Cache-Control") != "no-store" ||
		h.Get("X-Content-Type-Options") != "nosniff" || h.Get("Cross-Origin-Resource-Policy") != "same-origin" {
		t.Errorf("headers = %v", h)
	}
	if resp, body := c.do(http.MethodHead, "thumb/0", "", nil); resp.StatusCode != 200 || body != "" {
		t.Errorf("HEAD = %d %q", resp.StatusCode, body)
	}

	// Token, Host, method and fetch-metadata checks; none of these reach the handler.
	calls.Store(0)
	p := strconv.Itoa(c.port)
	for _, path := range []string{"/thumb/0", "/" + c.token + "x/thumb/0", "/" + strings.Repeat("0", 32) + "/thumb/0"} {
		if resp, _ := c.do(http.MethodGet, path, "", nil); resp.StatusCode != http.StatusNotFound {
			t.Errorf("GET %s = %d, want 404", path, resp.StatusCode)
		}
	}
	if resp, _ := c.do(http.MethodGet, "thumb/0", "", func(r *http.Request) { r.Host = "evil.com:" + p }); resp.StatusCode != http.StatusForbidden {
		t.Errorf("rebinding Host = %d", resp.StatusCode)
	}
	for _, site := range []string{"cross-site", "same-site", "none"} {
		if resp, _ := c.do(http.MethodGet, "thumb/0", "", func(r *http.Request) { r.Header.Set("Sec-Fetch-Site", site) }); resp.StatusCode != http.StatusForbidden {
			t.Errorf("Sec-Fetch-Site %s = %d, want 403", site, resp.StatusCode)
		}
	}
	for _, m := range []string{http.MethodPost, http.MethodPut, http.MethodDelete} {
		if resp, _ := c.do(m, "thumb/0", "{}", nil); resp.StatusCode != http.StatusMethodNotAllowed {
			t.Errorf("%s = %d, want 405", m, resp.StatusCode)
		}
	}
	for _, idx := range []string{"", "1", "3", "-1", "+0", "00", "01", "0x1", "1.0", "a", "0/", "0/x",
		"99999999", "1e2", " 0", "0%00", "..%2f..%2fetc%2fpasswd"} {
		if resp, _ := c.do(http.MethodGet, "thumb/"+idx, "", nil); resp.StatusCode != http.StatusNotFound {
			t.Errorf("thumb/%s = %d, want 404", idx, resp.StatusCode)
		}
	}
	if n := calls.Load(); n != 0 {
		t.Fatalf("Thumb ran %d times for refused requests", n)
	}
	if resp, _ := c.do(http.MethodGet, "thumb/0", "", func(r *http.Request) { r.Header.Set("Sec-Fetch-Site", "same-origin") }); resp.StatusCode != 200 {
		t.Errorf("same-origin fetch = %d", resp.StatusCode)
	}

	// Whatever the handler returns is checked before it is served.
	big := encodeJPEG(t, maxThumbDim+1, 10)
	gif := []byte("GIF89a\x01\x00\x01\x00\x00\x00\x00;")
	svg := []byte(`<svg xmlns="http://www.w3.org/2000/svg" onload="alert(1)"/>`)
	huge := append(append([]byte{}, small...), make([]byte, maxThumbBytes)...)
	pngData := encodePNG(t, image.NewGray(image.Rect(0, 0, 8, 8)))
	for name, r := range map[string]thumbReply{
		"not ok":          {small, "image/jpeg", false},
		"empty":           {nil, "image/jpeg", true},
		"too many pixels": {big, "image/jpeg", true},
		"too many bytes":  {huge, "image/jpeg", true},
		"wrong mime":      {small, "image/png", true},
		"html mime":       {small, "text/html", true},
		"gif":             {gif, "image/gif", true},
		"svg":             {svg, "image/svg+xml", true},
		"svg as png":      {svg, "image/png", true},
		"truncated":       {small[:40], "image/jpeg", true},
		"panic":           {nil, "panic", false},
	} {
		reply.Store(r)
		resp, body := c.do(http.MethodGet, "thumb/0", "", nil)
		if resp.StatusCode != http.StatusNotFound || strings.Contains(body, "secret") {
			t.Errorf("%s: %d %.40q", name, resp.StatusCode, body)
		}
	}
	reply.Store(thumbReply{pngData, "image/png", true})
	if resp, _ := c.do(http.MethodGet, "thumb/0", "", nil); resp.StatusCode != 200 || resp.Header.Get("Content-Type") != "image/png" {
		t.Errorf("png = %d %q", resp.StatusCode, resp.Header.Get("Content-Type"))
	}
}

func TestThumbRouteWithoutHandler(t *testing.T) {
	c := startUI(t, Handlers{Snapshot: func() Snapshot {
		return Snapshot{Phase: "receiving", Rows: []FileRow{{Name: "a.jpg", State: "verified", Thumb: true}}}
	}})
	if resp, _ := c.do(http.MethodGet, "thumb/0", "", nil); resp.StatusCode != http.StatusNotFound {
		t.Errorf("thumb without handler = %d", resp.StatusCode)
	}
}

func TestParseIndex(t *testing.T) {
	for in, want := range map[string]int{"0": 0, "7": 7, "42": 42, "9999999": 9999999} {
		if got, ok := parseIndex(in); !ok || got != want {
			t.Errorf("parseIndex(%q) = %d %v", in, got, ok)
		}
	}
	for _, in := range []string{"", "-1", "+1", "01", "1a", "10000000", "١"} {
		if _, ok := parseIndex(in); ok {
			t.Errorf("parseIndex(%q) accepted", in)
		}
	}
}

func TestThumbFromFile(t *testing.T) {
	dir := t.TempDir()
	write := func(name string, data []byte) string {
		p := filepath.Join(dir, name)
		if err := os.WriteFile(p, data, 0o600); err != nil {
			t.Fatal(err)
		}
		return p
	}
	check := func(name string, data []byte, mt string, ok bool, w, h int) {
		t.Helper()
		if !ok || mt != "image/jpeg" || !vetThumb(data, mt) {
			t.Fatalf("%s: ok=%v mime=%q vetted=%v", name, ok, mt, vetThumb(data, mt))
		}
		cfg, err := jpeg.DecodeConfig(bytes.NewReader(data))
		if err != nil || cfg.Width != w || cfg.Height != h {
			t.Errorf("%s: %dx%d (%v), want %dx%d", name, cfg.Width, cfg.Height, err, w, h)
		}
	}

	data, mt, ok := ThumbFromFile(write("wide.jpg", encodeJPEG(t, 1600, 1200)))
	check("wide jpeg", data, mt, ok, 320, 240)
	data, mt, ok = ThumbFromFile(write("tiny.jpg", encodeJPEG(t, 40, 30)))
	check("small jpeg is not enlarged", data, mt, ok, 40, 30)

	// A transparent PNG comes out over white.
	transparent := image.NewNRGBA(image.Rect(0, 0, 900, 300))
	data, mt, ok = ThumbFromFile(write("clear.png", encodePNG(t, transparent)))
	check("png", data, mt, ok, 320, 106)
	img, err := jpeg.Decode(bytes.NewReader(data))
	if err != nil {
		t.Fatal(err)
	}
	if r, g, b, _ := img.At(10, 10).RGBA(); r < 0xf000 || g < 0xf000 || b < 0xf000 {
		t.Errorf("transparent pixel = %x %x %x, want white", r, g, b)
	}

	// EXIF orientation 6 (phone held upright) swaps width and height.
	data, mt, ok = ThumbFromFile(write("rotated.jpg", withOrientation(t, encodeJPEG(t, 400, 200), 6)))
	check("rotated", data, mt, ok, 160, 320)

	for name, content := range map[string][]byte{
		"photo.heic": []byte("\x00\x00\x00\x18ftypheic\x00\x00\x00\x00heicmif1"),
		"broken.jpg": encodeJPEG(t, 50, 50)[:100],
		"empty.jpg":  {},
		"page.html":  []byte("<html><script>alert(1)</script>"),
	} {
		if _, _, ok := ThumbFromFile(write(name, content)); ok {
			t.Errorf("%s: made a thumbnail", name)
		}
	}
	if _, _, ok := ThumbFromFile(filepath.Join(dir, "missing.jpg")); ok {
		t.Error("missing file made a thumbnail")
	}
	if _, _, ok := ThumbFromFile(dir); ok {
		t.Error("a directory made a thumbnail")
	}
	// A decompression bomb: the header claims more pixels than allowed.
	bomb := encodePNG(t, image.NewGray(image.Rect(0, 0, 1, 1)))
	binary.BigEndian.PutUint32(bomb[16:], 20000) // IHDR width
	binary.BigEndian.PutUint32(bomb[20:], 20000) // IHDR height (CRC now wrong, never checked by DecodeConfig)
	if _, _, ok := thumbFromBytes(bomb); ok {
		t.Error("80+ megapixel picture accepted")
	}
}

// withOrientation inserts an EXIF APP1 segment with the given orientation after SOI.
func withOrientation(t *testing.T, jpg []byte, o uint16) []byte {
	t.Helper()
	tiff := []byte{'M', 'M', 0, 42, 0, 0, 0, 8, 0, 1, 0x01, 0x12, 0, 3, 0, 0, 0, 1, byte(o >> 8), byte(o), 0, 0, 0, 0, 0, 0}
	seg := append([]byte("Exif\x00\x00"), tiff...)
	app1 := []byte{0xFF, 0xE1, byte((len(seg) + 2) >> 8), byte(len(seg) + 2)}
	out := append([]byte{}, jpg[:2]...)
	out = append(out, app1...)
	out = append(out, seg...)
	return append(out, jpg[2:]...)
}

func TestExifOrientation(t *testing.T) {
	base := encodeJPEG(t, 8, 8)
	for o := uint16(1); o <= 8; o++ {
		if got := exifOrientation(withOrientation(t, base, o)); got != int(o) {
			t.Errorf("orientation %d read as %d", o, got)
		}
	}
	for name, b := range map[string][]byte{
		"none":      base,
		"bad value": withOrientation(t, base, 9),
		"not jpeg":  []byte("hello"),
		"cut":       withOrientation(t, base, 6)[:12],
		"empty":     nil,
	} {
		if got := exifOrientation(b); got != 1 {
			t.Errorf("%s: %d, want 1", name, got)
		}
	}
	// Orientations map pixels the right way: the top-left corner of a 2x1 picture.
	src := image.NewRGBA(image.Rect(0, 0, 2, 1))
	red := color.RGBA{255, 0, 0, 255}
	src.SetRGBA(0, 0, red)
	for o, at := range map[int]image.Point{1: {0, 0}, 2: {1, 0}, 3: {1, 0}, 6: {0, 0}, 8: {0, 1}} {
		if got := orient(src, o).RGBAAt(at.X, at.Y); got != red {
			t.Errorf("orientation %d: red not at %v", o, at)
		}
	}
}
