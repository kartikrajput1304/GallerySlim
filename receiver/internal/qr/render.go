package qr

import (
	"fmt"
	"strings"
)

// QuietZone is the light border around the symbol, in modules.
const QuietZone = 4

// SVG renders the symbol with a white background and a 4-module quiet zone. The image scales
// to its container; one user unit is one module.
func (c *Code) SVG() string {
	total := c.Size + 2*QuietZone
	var b strings.Builder
	fmt.Fprintf(&b, `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 %d %d" shape-rendering="crispEdges" role="img" aria-label="QR code">`, total, total)
	fmt.Fprintf(&b, `<rect width="%d" height="%d" fill="#ffffff"/>`, total, total)
	b.WriteString(`<path fill="#000000" d="`)
	for y := 0; y < c.Size; y++ {
		for x := 0; x < c.Size; {
			if !c.Dark(x, y) {
				x++
				continue
			}
			start := x
			for x < c.Size && c.Dark(x, y) {
				x++
			}
			fmt.Fprintf(&b, "M%d %dh%dv1h-%dz", start+QuietZone, y+QuietZone, x-start, x-start)
		}
	}
	b.WriteString(`"/></svg>`)
	return b.String()
}

// Terminal renders the symbol with half-block characters, two module rows per text line,
// including the quiet zone.
//
// With ansi=true it sets black-on-white colours, which scans on light and dark terminals.
// With ansi=false it assumes light text on a dark background (the usual terminal default) and
// draws light modules as blocks.
func (c *Code) Terminal(ansi bool) string {
	lo, hi := -QuietZone, c.Size+QuietZone
	var b strings.Builder
	for y := lo; y < hi; y += 2 {
		if ansi {
			b.WriteString("\x1b[30;107m")
		}
		for x := lo; x < hi; x++ {
			top := c.Dark(x, y)
			bottom := y+1 < hi && c.Dark(x, y+1)
			if y+1 >= hi {
				bottom = false
			}
			if !ansi {
				top, bottom = !top, !bottom
				if y+1 >= hi {
					bottom = false
				}
			}
			switch {
			case top && bottom:
				b.WriteString("█")
			case top:
				b.WriteString("▀")
			case bottom:
				b.WriteString("▄")
			default:
				b.WriteString(" ")
			}
		}
		if ansi {
			b.WriteString("\x1b[0m")
		}
		b.WriteByte('\n')
	}
	return b.String()
}

// Rows returns the module matrix (no quiet zone) as lines of '1' (dark) and '0' (light).
func (c *Code) Rows() []string {
	rows := make([]string, c.Size)
	line := make([]byte, c.Size)
	for y := 0; y < c.Size; y++ {
		for x := 0; x < c.Size; x++ {
			if c.Dark(x, y) {
				line[x] = '1'
			} else {
				line[x] = '0'
			}
		}
		rows[y] = string(line)
	}
	return rows
}
