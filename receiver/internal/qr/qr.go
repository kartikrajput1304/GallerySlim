// Package qr is a small QR Code encoder: byte mode, error correction level M, versions 1–10,
// Reed-Solomon over GF(256), all eight masks with penalty scoring, and format/version
// information (ISO/IEC 18004). It is enough for the gs2 pairing text (about 64 bytes, version 5).
package qr

import (
	"errors"
	"fmt"
)

// MaxVersion is the largest symbol version this encoder produces.
const MaxVersion = 10

// Error correction level M parameters per version (index 0 unused).
var (
	dataCodewordsM = [MaxVersion + 1]int{0, 16, 28, 44, 64, 86, 108, 124, 154, 182, 216}
	ecPerBlockM    = [MaxVersion + 1]int{0, 10, 16, 26, 18, 24, 16, 18, 22, 22, 26}
	numBlocksM     = [MaxVersion + 1]int{0, 1, 1, 1, 2, 2, 4, 4, 4, 5, 5}
	alignCenters   = [MaxVersion + 1][]int{
		nil, nil,
		{6, 18}, {6, 22}, {6, 26}, {6, 30}, {6, 34},
		{6, 22, 38}, {6, 24, 42}, {6, 26, 46}, {6, 28, 50},
	}
)

// ErrTooLong means the payload does not fit in a version-10 symbol at level M.
var ErrTooLong = errors.New("qr: payload too long")

// Code is an encoded QR symbol without its quiet zone.
type Code struct {
	Size    int // modules per side
	Version int
	Mask    int
	dark    []bool
	isFunc  []bool
}

// Dark reports whether the module at column x, row y is dark. Coordinates outside the symbol
// (the quiet zone) are light.
func (c *Code) Dark(x, y int) bool {
	if x < 0 || y < 0 || x >= c.Size || y >= c.Size {
		return false
	}
	return c.dark[y*c.Size+x]
}

// Capacity is the number of payload bytes a version holds at level M in byte mode.
func Capacity(version int) int {
	ccBits := 8
	if version >= 10 {
		ccBits = 16
	}
	return (dataCodewordsM[version]*8 - 4 - ccBits) / 8
}

// Encode encodes data in byte mode at level M using the smallest version that fits and the
// mask with the lowest penalty.
func Encode(data []byte) (*Code, error) { return EncodeMask(data, -1) }

// EncodeMask is Encode with a fixed mask (0–7), or automatic choice when mask is -1.
func EncodeMask(data []byte, mask int) (*Code, error) {
	if mask < -1 || mask > 7 {
		return nil, fmt.Errorf("qr: bad mask %d", mask)
	}
	version := 0
	for v := 1; v <= MaxVersion; v++ {
		if len(data) <= Capacity(v) {
			version = v
			break
		}
	}
	if version == 0 {
		return nil, ErrTooLong
	}
	codewords := addECCAndInterleave(version, dataCodewords(version, data))

	size := version*4 + 17
	c := &Code{Size: size, Version: version, dark: make([]bool, size*size), isFunc: make([]bool, size*size)}
	c.drawFunctionPatterns()
	c.drawCodewords(codewords)

	if mask == -1 {
		best := -1
		for m := 0; m < 8; m++ {
			c.applyMask(m)
			c.drawFormatBits(m)
			p := c.penalty()
			if best < 0 || p < best {
				best = p
				mask = m
			}
			c.applyMask(m) // XOR again to undo
		}
	}
	c.Mask = mask
	c.applyMask(mask)
	c.drawFormatBits(mask)
	c.isFunc = nil
	return c, nil
}

// dataCodewords builds the data codeword sequence: mode, count, bytes, terminator, padding.
func dataCodewords(version int, data []byte) []byte {
	capBits := dataCodewordsM[version] * 8
	var bb bitBuffer
	bb.append(0x4, 4) // byte mode
	if version >= 10 {
		bb.append(uint32(len(data)), 16)
	} else {
		bb.append(uint32(len(data)), 8)
	}
	for _, b := range data {
		bb.append(uint32(b), 8)
	}
	bb.append(0, min(4, capBits-bb.n))
	bb.append(0, (8-bb.n%8)%8)
	for pad := uint32(0xEC); bb.n < capBits; pad ^= 0xEC ^ 0x11 {
		bb.append(pad, 8)
	}
	return bb.bytes
}

type bitBuffer struct {
	bytes []byte
	n     int
}

func (b *bitBuffer) append(val uint32, bits int) {
	for i := bits - 1; i >= 0; i-- {
		if b.n%8 == 0 {
			b.bytes = append(b.bytes, 0)
		}
		if (val>>uint(i))&1 != 0 {
			b.bytes[b.n/8] |= 0x80 >> uint(b.n%8)
		}
		b.n++
	}
}

// addECCAndInterleave splits data into blocks, appends Reed-Solomon codewords to each and
// interleaves them.
func addECCAndInterleave(version int, data []byte) []byte {
	nb := numBlocksM[version]
	ecLen := ecPerBlockM[version]
	shortLen := len(data) / nb
	numLong := len(data) % nb
	gen := rsGenerator(ecLen)

	blocks := make([][]byte, nb)
	ecs := make([][]byte, nb)
	k := 0
	for i := 0; i < nb; i++ {
		l := shortLen
		if i >= nb-numLong {
			l++
		}
		blocks[i] = data[k : k+l]
		k += l
		ecs[i] = rsRemainder(blocks[i], gen)
	}
	out := make([]byte, 0, len(data)+nb*ecLen)
	for i := 0; i <= shortLen; i++ {
		for j := 0; j < nb; j++ {
			if i < len(blocks[j]) {
				out = append(out, blocks[j][i])
			}
		}
	}
	for i := 0; i < ecLen; i++ {
		for j := 0; j < nb; j++ {
			out = append(out, ecs[j][i])
		}
	}
	return out
}

// gfMul multiplies in GF(2^8) modulo x^8 + x^4 + x^3 + x^2 + 1 (0x11D).
func gfMul(x, y byte) byte {
	var z uint16
	for i := 7; i >= 0; i-- {
		z = (z << 1) ^ ((z >> 7) * 0x11D)
		if (y>>uint(i))&1 != 0 {
			z ^= uint16(x)
		}
	}
	return byte(z)
}

// rsGenerator returns the coefficients (highest power first, leading 1 omitted) of
// prod_{i=0}^{degree-1} (x - α^i).
func rsGenerator(degree int) []byte {
	g := make([]byte, degree)
	g[degree-1] = 1
	root := byte(1)
	for i := 0; i < degree; i++ {
		for j := 0; j < degree; j++ {
			g[j] = gfMul(g[j], root)
			if j+1 < degree {
				g[j] ^= g[j+1]
			}
		}
		root = gfMul(root, 0x02)
	}
	return g
}

func rsRemainder(data, gen []byte) []byte {
	rem := make([]byte, len(gen))
	for _, b := range data {
		factor := b ^ rem[0]
		copy(rem, rem[1:])
		rem[len(rem)-1] = 0
		for i := range rem {
			rem[i] ^= gfMul(gen[i], factor)
		}
	}
	return rem
}

func (c *Code) set(x, y int, dark bool) {
	c.dark[y*c.Size+x] = dark
	c.isFunc[y*c.Size+x] = true
}

func (c *Code) drawFunctionPatterns() {
	n := c.Size
	for i := 0; i < n; i++ {
		c.set(6, i, i%2 == 0)
		c.set(i, 6, i%2 == 0)
	}
	c.drawFinder(3, 3)
	c.drawFinder(n-4, 3)
	c.drawFinder(3, n-4)
	pos := alignCenters[c.Version]
	for i, y := range pos {
		for j, x := range pos {
			last := len(pos) - 1
			if (i == 0 && j == 0) || (i == 0 && j == last) || (i == last && j == 0) {
				continue // overlaps a finder pattern
			}
			for dy := -2; dy <= 2; dy++ {
				for dx := -2; dx <= 2; dx++ {
					c.set(x+dx, y+dy, max(abs(dx), abs(dy)) != 1)
				}
			}
		}
	}
	c.drawFormatBits(0) // reserve the areas; redrawn with the real mask later
	c.drawVersion()
}

func (c *Code) drawFinder(cx, cy int) {
	for dy := -4; dy <= 4; dy++ {
		for dx := -4; dx <= 4; dx++ {
			x, y := cx+dx, cy+dy
			if x < 0 || y < 0 || x >= c.Size || y >= c.Size {
				continue
			}
			d := max(abs(dx), abs(dy))
			c.set(x, y, d != 2 && d != 4)
		}
	}
}

// formatBits returns the 15-bit format information for level M and the given mask.
func formatBits(mask int) uint32 {
	data := uint32(0)<<3 | uint32(mask) // level M = 00
	rem := data
	for i := 0; i < 10; i++ {
		rem = (rem << 1) ^ ((rem >> 9) * 0x537)
	}
	return (data<<10 | rem) ^ 0x5412
}

// versionBits returns the 18-bit version information (versions 7 and up).
func versionBits(version int) uint32 {
	rem := uint32(version)
	for i := 0; i < 12; i++ {
		rem = (rem << 1) ^ ((rem >> 11) * 0x1F25)
	}
	return uint32(version)<<12 | rem
}

func bit(v uint32, i int) bool { return (v>>uint(i))&1 != 0 }

func (c *Code) drawFormatBits(mask int) {
	bits := formatBits(mask)
	n := c.Size
	for i := 0; i <= 5; i++ {
		c.set(8, i, bit(bits, i))
	}
	c.set(8, 7, bit(bits, 6))
	c.set(8, 8, bit(bits, 7))
	c.set(7, 8, bit(bits, 8))
	for i := 9; i < 15; i++ {
		c.set(14-i, 8, bit(bits, i))
	}
	for i := 0; i < 8; i++ {
		c.set(n-1-i, 8, bit(bits, i))
	}
	for i := 8; i < 15; i++ {
		c.set(8, n-15+i, bit(bits, i))
	}
	c.set(8, n-8, true) // the dark module
}

func (c *Code) drawVersion() {
	if c.Version < 7 {
		return
	}
	bits := versionBits(c.Version)
	for i := 0; i < 18; i++ {
		a := c.Size - 11 + i%3
		b := i / 3
		c.set(a, b, bit(bits, i))
		c.set(b, a, bit(bits, i))
	}
}

// drawCodewords places the codeword bits in the zigzag order, skipping function modules.
// Remainder bits stay light.
func (c *Code) drawCodewords(data []byte) {
	n := c.Size
	i := 0
	total := len(data) * 8
	for right := n - 1; right >= 1; right -= 2 {
		if right == 6 {
			right = 5
		}
		for vert := 0; vert < n; vert++ {
			for j := 0; j < 2; j++ {
				x := right - j
				upward := (right+1)&2 == 0
				y := vert
				if upward {
					y = n - 1 - vert
				}
				if c.isFunc[y*n+x] {
					continue
				}
				if i < total {
					c.dark[y*n+x] = (data[i>>3]>>(7-uint(i&7)))&1 != 0
					i++
				}
			}
		}
	}
}

func maskBit(mask, x, y int) bool {
	switch mask {
	case 0:
		return (x+y)%2 == 0
	case 1:
		return y%2 == 0
	case 2:
		return x%3 == 0
	case 3:
		return (x+y)%3 == 0
	case 4:
		return (x/3+y/2)%2 == 0
	case 5:
		return x*y%2+x*y%3 == 0
	case 6:
		return (x*y%2+x*y%3)%2 == 0
	default:
		return ((x+y)%2+x*y%3)%2 == 0
	}
}

func (c *Code) applyMask(mask int) {
	n := c.Size
	for y := 0; y < n; y++ {
		for x := 0; x < n; x++ {
			if !c.isFunc[y*n+x] && maskBit(mask, x, y) {
				c.dark[y*n+x] = !c.dark[y*n+x]
			}
		}
	}
}

// penalty scores the symbol with the four rules of ISO/IEC 18004 §7.8.3.
func (c *Code) penalty() int {
	n := c.Size
	p := 0
	line := make([]bool, n)
	for pass := 0; pass < 2; pass++ {
		for a := 0; a < n; a++ {
			for b := 0; b < n; b++ {
				if pass == 0 {
					line[b] = c.dark[a*n+b] // row a
				} else {
					line[b] = c.dark[b*n+a] // column a
				}
			}
			p += linePenalty(line)
		}
	}
	// Rule 2: 2x2 blocks of one colour.
	for y := 0; y < n-1; y++ {
		for x := 0; x < n-1; x++ {
			d := c.dark[y*n+x]
			if d == c.dark[y*n+x+1] && d == c.dark[(y+1)*n+x] && d == c.dark[(y+1)*n+x+1] {
				p += 3
			}
		}
	}
	// Rule 4: balance of dark modules, 10 points per 5% away from 50%.
	dark := 0
	for _, d := range c.dark {
		if d {
			dark++
		}
	}
	total := n * n
	dev := abs(dark*100 - total*50) // 100 × |ratio − 50%| × total
	p += 10 * (dev / (5 * total))
	return p
}

// linePenalty applies rules 1 and 3 to one row or column. Modules beyond the edge count as light.
func linePenalty(line []bool) int {
	p := 0
	run := 1
	for i := 1; i <= len(line); i++ {
		if i < len(line) && line[i] == line[i-1] {
			run++
			continue
		}
		if run >= 5 {
			p += 3 + run - 5
		}
		run = 1
	}
	at := func(i int) bool { return i >= 0 && i < len(line) && line[i] }
	pattern := [7]bool{true, false, true, true, true, false, true}
	for i := 0; i+7 <= len(line); i++ {
		match := true
		for k := 0; k < 7; k++ {
			if line[i+k] != pattern[k] {
				match = false
				break
			}
		}
		if !match {
			continue
		}
		lightBefore, lightAfter := true, true
		for k := 1; k <= 4; k++ {
			if at(i - k) {
				lightBefore = false
			}
			if at(i + 6 + k) {
				lightAfter = false
			}
		}
		if lightBefore || lightAfter {
			p += 40
		}
	}
	return p
}

func abs(x int) int {
	if x < 0 {
		return -x
	}
	return x
}
