package store

import (
	"crypto/sha256"
	"encoding/hex"
	"regexp"
	"strconv"
	"strings"
	"unicode"
	"unicode/utf8"

	"galleryslim/receiver/internal/textsafe"
)

const (
	// MaxNameBytes is the longest file name (in UTF-8 bytes) the receiver writes.
	MaxNameBytes = 255
	maxExtBytes  = 16
	// Undated is the folder for files without a known date.
	Undated = "undated"
)

var monthRe = regexp.MustCompile(`^[0-9]{4}-(0[1-9]|1[0-2])$`)

// MonthDir returns the month folder name: "yyyy-MM" when month is well formed, else "undated".
func MonthDir(month string) string {
	if monthRe.MatchString(month) {
		return month
	}
	return Undated
}

var reservedWindows = func() map[string]bool {
	m := map[string]bool{"CON": true, "PRN": true, "AUX": true, "NUL": true, "CONIN$": true, "CONOUT$": true}
	for _, d := range []string{"0", "1", "2", "3", "4", "5", "6", "7", "8", "9", "¹", "²", "³"} {
		m["COM"+d] = true
		m["LPT"+d] = true
	}
	return m
}()

// isReservedWindows reports names Windows maps to devices, with or without an extension
// ("CON", "con.jpg", "LPT1 .png").
func isReservedWindows(name string) bool {
	base, _, _ := strings.Cut(name, ".")
	base = strings.TrimRight(base, " ")
	return reservedWindows[strings.ToUpper(base)]
}

var mimeExt = map[string]string{
	"image/jpeg": ".jpg", "image/png": ".png", "image/heic": ".heic", "image/heif": ".heif",
	"image/webp": ".webp", "image/gif": ".gif", "image/avif": ".avif", "image/x-adobe-dng": ".dng",
	"image/dng": ".dng", "video/mp4": ".mp4", "video/quicktime": ".mov", "video/3gpp": ".3gp",
	"video/webm": ".webm", "video/x-matroska": ".mkv",
}

// splitExt splits off a short extension ("IMG_1.jpg" → "IMG_1", ".jpg").
func splitExt(s string) (stem, ext string) {
	i := strings.LastIndexByte(s, '.')
	if i <= 0 || i == len(s)-1 || len(s)-i > maxExtBytes {
		return s, ""
	}
	return s[:i], s[i:]
}

// truncateUTF8 cuts s to at most n bytes without splitting a character.
func truncateUTF8(s string, n int) string {
	if len(s) <= n {
		return s
	}
	for n > 0 && !utf8.RuneStart(s[n]) {
		n--
	}
	return s[:n]
}

// CleanName turns an untrusted file name from the phone into one that is safe on Windows,
// macOS and Linux: one path component, no control or bidi characters, none of <>:"/\|?*,
// no trailing dots or spaces, no leading dot, not a Windows device name, at most 255 bytes.
// If the name has no extension, one is taken from mime when known. CleanName is idempotent
// for an empty mime.
func CleanName(name, mime string) string {
	name = strings.ToValidUTF8(name, "")
	var b strings.Builder
	for _, r := range name {
		switch {
		case unicode.IsControl(r), textsafe.IsBidiControl(r), r == 0x2028, r == 0x2029, r == 0xFEFF:
			// dropped
		case strings.ContainsRune(`<>:"/\|?*`, r):
			b.WriteByte('_')
		default:
			b.WriteRune(r)
		}
	}
	s := strings.TrimLeft(strings.TrimRight(b.String(), " ."), " ")
	if strings.HasPrefix(s, ".") {
		s = "_" + s // hidden on macOS/Linux, and covers "." and ".."
	}
	stem, ext := splitExt(s)
	if ext == "" {
		ext = mimeExt[strings.ToLower(strings.TrimSpace(mime))]
	}
	if strings.TrimRight(stem, " ") == "" {
		stem = "photo"
	}
	if isReservedWindows(stem + ext) {
		stem = "_" + stem
	}
	return compose(stem, ext, 1)
}

// compose joins stem and extension with " (n)" for n ≥ 2, shortening the stem so the whole
// name fits in MaxNameBytes.
func compose(stem, ext string, n int) string {
	suffix := ""
	if n >= 2 {
		suffix = " (" + strconv.Itoa(n) + ")"
	}
	stem = truncateUTF8(stem, MaxNameBytes-len(ext)-len(suffix))
	stem = strings.TrimRight(stem, " .")
	if stem == "" {
		stem = "photo"
	}
	return stem + suffix + ext
}

// WithSuffix returns the n-th candidate for a clean name: n=1 is the name itself, then
// "name (2).ext", "name (3).ext", ...
func WithSuffix(name string, n int) string {
	if n <= 1 {
		return name
	}
	stem, ext := splitExt(name)
	return compose(stem, ext, n)
}

var (
	safeIDRe   = regexp.MustCompile(`^[A-Za-z0-9_-]{1,40}$`)
	partNameRe = regexp.MustCompile(`^[A-Za-z0-9_-]{1,40}-[0-9]{1,19}-[0-9a-f]{12}\.part$`)
)

// PartName names the partial download of a file so that a later session for the same phone
// file continues it: it is derived from the file id and size (plus the phone name, date taken
// and any listed hash, so two phones or two versions of a file don't share one).
func PartName(phone, fileID string, size, takenMs int64, sha string) string {
	id := fileID
	if !safeIDRe.MatchString(id) {
		id = "f"
	}
	h := sha256.New()
	for _, s := range []string{phone, fileID, strconv.FormatInt(size, 10), strconv.FormatInt(takenMs, 10), sha} {
		h.Write([]byte(s))
		h.Write([]byte{0})
	}
	return id + "-" + strconv.FormatInt(size, 10) + "-" + hex.EncodeToString(h.Sum(nil)[:6]) + ".part"
}
