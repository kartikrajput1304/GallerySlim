package ui

import (
	"encoding/xml"
	"io"
	"regexp"
	"strings"
	"unicode/utf8"
)

const (
	svgNS       = "http://www.w3.org/2000/svg"
	maxSVGBytes = 1 << 20
)

// onAttr matches an event-handler attribute such as onload= or ONERROR =.
var onAttr = regexp.MustCompile(`(?i)\bon[a-z]+\s*=`)

// The elements and attributes our QR encoder emits (plus <g> and a few geometry attributes).
// Anything else, including style, href, xlink and foreignObject, is refused.
var (
	svgElements = map[string]bool{"svg": true, "g": true, "rect": true, "path": true}
	svgAttrs    = map[string]bool{
		"xmlns": true, "viewBox": true, "width": true, "height": true, "x": true, "y": true,
		"fill": true, "d": true, "shape-rendering": true, "role": true, "aria-label": true,
	}
)

// vetSVG reports whether s is a QR code SVG that is safe to insert into the page as markup:
// it starts with <svg, has no script, event handler, link, entity, comment, doctype or
// processing instruction, and is a single <svg> element built only from the allowed
// elements and attributes. The page repeats the same checks before inserting it.
func vetSVG(s string) bool {
	if s == "" || len(s) > maxSVGBytes || !utf8.ValidString(s) || !strings.HasPrefix(s, "<svg") {
		return false
	}
	low := strings.ToLower(s)
	for _, bad := range []string{"<script", "href", "<!", "<?", "&", "url(", "javascript:"} {
		if strings.Contains(low, bad) {
			return false
		}
	}
	if onAttr.MatchString(s) {
		return false
	}
	dec := xml.NewDecoder(strings.NewReader(s))
	dec.Strict = true
	depth, roots := 0, 0
	for {
		tok, err := dec.Token()
		if err == io.EOF {
			break
		}
		if err != nil {
			return false
		}
		switch t := tok.(type) {
		case xml.StartElement:
			if depth == 0 {
				roots++
				if roots > 1 || t.Name.Local != "svg" {
					return false
				}
			}
			if (t.Name.Space != "" && t.Name.Space != svgNS) || !svgElements[t.Name.Local] {
				return false
			}
			for _, a := range t.Attr {
				if a.Name.Space != "" || !svgAttrs[a.Name.Local] {
					return false
				}
				if a.Name.Local == "xmlns" && a.Value != svgNS {
					return false
				}
			}
			depth++
		case xml.EndElement:
			depth--
		case xml.CharData:
			if strings.TrimSpace(string(t)) != "" {
				return false
			}
		default: // comments, processing instructions, directives
			return false
		}
	}
	return roots == 1 && depth == 0
}
