// Package textsafe cleans untrusted text received from the phone before it is shown or logged.
package textsafe

import (
	"strings"
	"unicode"
	"unicode/utf8"
)

// IsBidiControl reports the Unicode bidirectional formatting characters (they can make
// "photo‮gpj.exe" look like "photoexe.jpg").
func IsBidiControl(r rune) bool {
	switch {
	case r == 0x061C, r == 0x200E, r == 0x200F:
		return true
	case r >= 0x202A && r <= 0x202E:
		return true
	case r >= 0x2066 && r <= 0x2069:
		return true
	}
	return false
}

// dropRune reports characters that are removed from untrusted text: control characters,
// bidi controls, line/paragraph separators, BOM/zero-width no-break space and the
// replacement-prone non-characters.
func dropRune(r rune) bool {
	if r == utf8.RuneError || unicode.IsControl(r) || IsBidiControl(r) {
		return true
	}
	switch r {
	case 0x2028, 0x2029, 0xFEFF, 0xFFFE, 0xFFFF:
		return true
	}
	return false
}

// Clean returns s as valid UTF-8 with control and bidi characters removed, whitespace
// collapsed, and at most maxRunes characters.
func Clean(s string, maxRunes int) string {
	s = strings.ToValidUTF8(s, "")
	var b strings.Builder
	n := 0
	space := false
	for _, r := range s {
		if unicode.IsSpace(r) && r != 0x2028 && r != 0x2029 {
			space = b.Len() > 0
			continue
		}
		if dropRune(r) {
			continue
		}
		if space {
			if n >= maxRunes {
				break
			}
			b.WriteByte(' ')
			n++
			space = false
		}
		if n >= maxRunes {
			break
		}
		b.WriteRune(r)
		n++
	}
	return b.String()
}
