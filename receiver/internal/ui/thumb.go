package ui

import (
	"bytes"
	"encoding/binary"
	"image"
	"image/color"
	"image/jpeg"
	_ "image/png" // registers the PNG decoder for image.Decode and image.DecodeConfig
	"io"
	"net/http"
	"os"
	"strconv"
)

// Thumbnails: the page shows the latest received photos as small pictures loaded from
// thumb/<rowIndex>. They are served under the same token, Host and same-origin checks as
// the page, never cached, and only after vetThumb has checked that the bytes really are a
// small JPEG or PNG.

const (
	maxThumbBytes   = 256 << 10 // largest picture thumb/<n> will serve
	maxThumbDim     = 640       // largest width or height thumb/<n> will serve
	maxThumbCalls   = 2         // concurrent Handlers.Thumb calls
	thumbBox        = 320       // ThumbFromFile fits pictures into thumbBox x thumbBox
	thumbQuality    = 78
	maxSourceBytes  = 80 << 20   // ThumbFromFile refuses larger files
	maxSourcePixels = 80_000_000 // and larger pictures (a decompression-bomb guard)
	maxIndexDigits  = 7
)

func (s *server) serveThumb(w http.ResponseWriter, r *http.Request, idx string) {
	if !allowMethods(w, r, http.MethodGet, http.MethodHead) {
		return
	}
	// Pictures are only for this page; another site cannot even embed them.
	if site := r.Header.Get("Sec-Fetch-Site"); site != "" && site != "same-origin" {
		http.Error(w, "Forbidden", http.StatusForbidden)
		return
	}
	i, ok := parseIndex(idx)
	if !ok || s.h.Thumb == nil {
		http.NotFound(w, r)
		return
	}
	snap, ok := s.snapshot()
	if !ok || i >= len(snap.Rows) || !snap.Rows[i].Thumb {
		http.NotFound(w, r)
		return
	}
	select {
	case s.thumbSlots <- struct{}{}:
	case <-r.Context().Done():
		return
	}
	data, mt, ok := s.callThumb(i)
	<-s.thumbSlots
	if !ok || !vetThumb(data, mt) {
		http.NotFound(w, r)
		return
	}
	w.Header().Set("Content-Type", mt)
	w.Header().Set("Content-Length", strconv.Itoa(len(data)))
	if r.Method == http.MethodHead {
		return
	}
	_, _ = w.Write(data)
}

// callThumb calls Handlers.Thumb, turning a panic into ok=false.
func (s *server) callThumb(i int) (data []byte, mt string, ok bool) {
	defer func() {
		if recover() != nil {
			data, mt, ok = nil, "", false
		}
	}()
	return s.h.Thumb(i)
}

// parseIndex accepts a plain decimal row index: digits only, no sign, no leading zeros.
func parseIndex(s string) (int, bool) {
	if s == "" || len(s) > maxIndexDigits || (len(s) > 1 && s[0] == '0') {
		return 0, false
	}
	n := 0
	for i := 0; i < len(s); i++ {
		c := s[i]
		if c < '0' || c > '9' {
			return 0, false
		}
		n = n*10 + int(c-'0')
	}
	return n, true
}

// vetThumb reports whether data is a small JPEG or PNG whose bytes match mt.
func vetThumb(data []byte, mt string) bool {
	if len(data) == 0 || len(data) > maxThumbBytes {
		return false
	}
	var format string
	switch mt {
	case "image/jpeg":
		format = "jpeg"
	case "image/png":
		format = "png"
	default:
		return false
	}
	if http.DetectContentType(data) != mt {
		return false
	}
	cfg, got, err := image.DecodeConfig(bytes.NewReader(data))
	return err == nil && got == format && cfg.Width > 0 && cfg.Height > 0 &&
		cfg.Width <= maxThumbDim && cfg.Height <= maxThumbDim
}

// ThumbFromFile makes a small JPEG (at most 320 x 320, upright per the photo's EXIF
// orientation, transparency over white) from a JPEG or PNG file. It returns ok=false for
// other formats such as HEIC, for files over 80 MB and for pictures over 80 megapixels.
// It reads only path; callers must pass a file they saved themselves.
func ThumbFromFile(path string) (data []byte, mime string, ok bool) {
	f, err := os.Open(path)
	if err != nil {
		return nil, "", false
	}
	defer f.Close()
	if fi, err := f.Stat(); err != nil || !fi.Mode().IsRegular() || fi.Size() > maxSourceBytes {
		return nil, "", false
	}
	src, err := io.ReadAll(io.LimitReader(f, maxSourceBytes+1))
	if err != nil || len(src) > maxSourceBytes {
		return nil, "", false
	}
	return thumbFromBytes(src)
}

func thumbFromBytes(src []byte) ([]byte, string, bool) {
	cfg, format, err := image.DecodeConfig(bytes.NewReader(src))
	if err != nil || (format != "jpeg" && format != "png") || cfg.Width <= 0 || cfg.Height <= 0 ||
		int64(cfg.Width)*int64(cfg.Height) > maxSourcePixels {
		return nil, "", false
	}
	img, _, err := image.Decode(bytes.NewReader(src))
	if err != nil {
		return nil, "", false
	}
	small := downscale(img, thumbBox)
	if format == "jpeg" {
		small = orient(small, exifOrientation(src))
	}
	var out bytes.Buffer
	if err := jpeg.Encode(&out, small, &jpeg.Options{Quality: thumbQuality}); err != nil || out.Len() > maxThumbBytes {
		return nil, "", false
	}
	return out.Bytes(), "image/jpeg", true
}

// downscale fits img into a box x box square (never enlarging) by averaging up to 4 x 4
// samples per output pixel, and composites any transparency over white.
func downscale(img image.Image, box int) *image.RGBA {
	b := img.Bounds()
	sw, sh := b.Dx(), b.Dy()
	dw, dh := sw, sh
	if sw > box || sh > box {
		if sw >= sh {
			dw, dh = box, max(1, int(int64(sh)*int64(box)/int64(sw)))
		} else {
			dw, dh = max(1, int(int64(sw)*int64(box)/int64(sh))), box
		}
	}
	dst := image.NewRGBA(image.Rect(0, 0, dw, dh))
	for dy := 0; dy < dh; dy++ {
		y0, y1 := dy*sh/dh, max((dy+1)*sh/dh, dy*sh/dh+1)
		ny := min(4, y1-y0)
		for dx := 0; dx < dw; dx++ {
			x0, x1 := dx*sw/dw, max((dx+1)*sw/dw, dx*sw/dw+1)
			nx := min(4, x1-x0)
			var r, g, bl, a uint64
			for j := 0; j < ny; j++ {
				sy := b.Min.Y + y0 + (2*j+1)*(y1-y0)/(2*ny)
				for i := 0; i < nx; i++ {
					sx := b.Min.X + x0 + (2*i+1)*(x1-x0)/(2*nx)
					cr, cg, cb, ca := img.At(sx, sy).RGBA() // premultiplied, 16-bit
					r, g, bl, a = r+uint64(cr), g+uint64(cg), bl+uint64(cb), a+uint64(ca)
				}
			}
			n := uint64(nx * ny)
			white := 0xffff - a/n // premultiplied colour over a white background
			dst.SetRGBA(dx, dy, color.RGBA{
				R: uint8((r/n + white) >> 8), G: uint8((g/n + white) >> 8),
				B: uint8((bl/n + white) >> 8), A: 0xff,
			})
		}
	}
	return dst
}

// orient turns an image the way EXIF orientation o (1-8) says the camera held it.
func orient(src *image.RGBA, o int) *image.RGBA {
	if o < 2 || o > 8 {
		return src
	}
	w, h := src.Bounds().Dx(), src.Bounds().Dy()
	dw, dh := w, h
	if o >= 5 {
		dw, dh = h, w
	}
	dst := image.NewRGBA(image.Rect(0, 0, dw, dh))
	for y := 0; y < dh; y++ {
		for x := 0; x < dw; x++ {
			var sx, sy int
			switch o {
			case 2:
				sx, sy = w-1-x, y
			case 3:
				sx, sy = w-1-x, h-1-y
			case 4:
				sx, sy = x, h-1-y
			case 5:
				sx, sy = y, x
			case 6:
				sx, sy = y, h-1-x
			case 7:
				sx, sy = w-1-y, h-1-x
			case 8:
				sx, sy = w-1-y, x
			}
			dst.SetRGBA(x, y, src.RGBAAt(sx, sy))
		}
	}
	return dst
}

// exifOrientation reads the orientation tag (0x0112) from a JPEG's EXIF block, or 1.
func exifOrientation(b []byte) int {
	if len(b) < 4 || b[0] != 0xFF || b[1] != 0xD8 {
		return 1
	}
	p := 2
	for p+4 <= len(b) {
		if b[p] != 0xFF {
			return 1
		}
		marker := b[p+1]
		if marker == 0xFF { // fill byte
			p++
			continue
		}
		if marker == 0xDA || marker == 0xD9 { // start of scan / end of image
			return 1
		}
		n := int(binary.BigEndian.Uint16(b[p+2:]))
		if n < 2 || p+2+n > len(b) {
			return 1
		}
		seg := b[p+4 : p+2+n]
		if marker == 0xE1 && len(seg) >= 6 && string(seg[:6]) == "Exif\x00\x00" {
			return tiffOrientation(seg[6:])
		}
		p += 2 + n
	}
	return 1
}

func tiffOrientation(t []byte) int {
	if len(t) < 8 {
		return 1
	}
	var bo binary.ByteOrder
	switch string(t[:2]) {
	case "II":
		bo = binary.LittleEndian
	case "MM":
		bo = binary.BigEndian
	default:
		return 1
	}
	if bo.Uint16(t[2:]) != 42 {
		return 1
	}
	ifd := int64(bo.Uint32(t[4:]))
	if ifd < 8 || ifd+2 > int64(len(t)) {
		return 1
	}
	count := int64(bo.Uint16(t[ifd:]))
	for i := int64(0); i < count; i++ {
		e := ifd + 2 + 12*i
		if e+12 > int64(len(t)) {
			return 1
		}
		if bo.Uint16(t[e:]) == 0x0112 && bo.Uint16(t[e+2:]) == 3 { // SHORT
			if o := int(bo.Uint16(t[e+8:])); o >= 1 && o <= 8 {
				return o
			}
			return 1
		}
	}
	return 1
}
