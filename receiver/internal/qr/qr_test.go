package qr

import (
	"bytes"
	"encoding/json"
	"flag"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

var update = flag.Bool("update", false, "rewrite testdata/qr-fixtures")

func TestFormatBitsTable(t *testing.T) {
	// ISO/IEC 18004 Table C.1, error correction level M.
	want := []string{
		"101010000010010", "101000100100101", "101111001111100", "101101101001011",
		"100010111111001", "100000011001110", "100111110010111", "100101010100000",
	}
	for m, w := range want {
		if got := fmt.Sprintf("%015b", formatBits(m)); got != w {
			t.Errorf("mask %d: %s, want %s", m, got, w)
		}
	}
}

func TestVersionBits(t *testing.T) {
	want := map[int]uint32{7: 0x07C94, 8: 0x085BC, 9: 0x09A99, 10: 0x0A4D3}
	for v, w := range want {
		if got := versionBits(v); got != w {
			t.Errorf("version %d: %05X, want %05X", v, got, w)
		}
	}
}

func TestReedSolomonKnownExample(t *testing.T) {
	// "HELLO WORLD" as 1-M (alphanumeric), a widely published worked example.
	data := []byte{32, 91, 11, 120, 209, 114, 220, 77, 67, 64, 236, 17, 236, 17, 236, 17}
	want := []byte{196, 35, 39, 119, 235, 215, 231, 226, 93, 23}
	if got := rsRemainder(data, rsGenerator(10)); !bytes.Equal(got, want) {
		t.Fatalf("ecc = %v, want %v", got, want)
	}
}

func TestCapacity(t *testing.T) {
	want := map[int]int{1: 14, 2: 26, 3: 42, 4: 62, 5: 84, 6: 106, 7: 122, 8: 152, 9: 180, 10: 213}
	for v, w := range want {
		if got := Capacity(v); got != w {
			t.Errorf("v%d capacity %d, want %d", v, got, w)
		}
	}
	if _, err := Encode(make([]byte, 214)); err != ErrTooLong {
		t.Errorf("214 bytes: %v", err)
	}
}

// --- a small independent reader used to check the encoder's output ---

func functionMap(version int) []bool {
	size := version*4 + 17
	c := &Code{Size: size, Version: version, dark: make([]bool, size*size), isFunc: make([]bool, size*size)}
	c.drawFunctionPatterns()
	return c.isFunc
}

func readFormat(c *Code) (uint32, uint32) {
	n := c.Size
	var a, b uint32
	get := func(x, y int) uint32 {
		if c.Dark(x, y) {
			return 1
		}
		return 0
	}
	for i := 0; i <= 5; i++ {
		a |= get(8, i) << uint(i)
	}
	a |= get(8, 7) << 6
	a |= get(8, 8) << 7
	a |= get(7, 8) << 8
	for i := 9; i < 15; i++ {
		a |= get(14-i, 8) << uint(i)
	}
	for i := 0; i < 8; i++ {
		b |= get(n-1-i, 8) << uint(i)
	}
	for i := 8; i < 15; i++ {
		b |= get(8, n-15+i) << uint(i)
	}
	return a, b
}

func decode(t *testing.T, c *Code) []byte {
	t.Helper()
	n := c.Size
	fa, fb := readFormat(c)
	if fa != fb {
		t.Fatalf("format copies differ: %015b %015b", fa, fb)
	}
	mask := -1
	for m := 0; m < 8; m++ {
		if formatBits(m) == fa {
			mask = m
		}
	}
	if mask != c.Mask {
		t.Fatalf("format says mask %d, code says %d", mask, c.Mask)
	}
	if !c.Dark(8, n-8) {
		t.Fatal("dark module missing")
	}
	isFunc := functionMap(c.Version)
	var bits []bool
	for right := n - 1; right >= 1; right -= 2 {
		if right == 6 {
			right = 5
		}
		for vert := 0; vert < n; vert++ {
			for j := 0; j < 2; j++ {
				x := right - j
				y := vert
				if (right+1)&2 == 0 {
					y = n - 1 - vert
				}
				if isFunc[y*n+x] {
					continue
				}
				bits = append(bits, c.Dark(x, y) != maskBit(mask, x, y))
			}
		}
	}
	total := (len(bits) / 8)
	cw := make([]byte, total)
	for i := 0; i < total*8; i++ {
		if bits[i] {
			cw[i/8] |= 0x80 >> uint(i%8)
		}
	}
	// De-interleave.
	nb := numBlocksM[c.Version]
	ecLen := ecPerBlockM[c.Version]
	dataLen := dataCodewordsM[c.Version]
	if dataLen+nb*ecLen != total {
		t.Fatalf("v%d: %d codewords, tables say %d", c.Version, total, dataLen+nb*ecLen)
	}
	shortLen := dataLen / nb
	numLong := dataLen % nb
	blocks := make([][]byte, nb)
	k := 0
	for i := 0; i <= shortLen; i++ {
		for j := 0; j < nb; j++ {
			l := shortLen
			if j >= nb-numLong {
				l++
			}
			if i < l {
				blocks[j] = append(blocks[j], cw[k])
				k++
			}
		}
	}
	for i := 0; i < ecLen; i++ {
		for j := 0; j < nb; j++ {
			blocks[j] = append(blocks[j], cw[k])
			k++
		}
	}
	var data []byte
	for j, blk := range blocks {
		// Syndromes S_i = blk(α^i) must all be zero for a valid codeword.
		alpha := byte(1)
		for i := 0; i < ecLen; i++ {
			var s byte
			for _, b := range blk {
				s = gfMul(s, alpha) ^ b
			}
			if s != 0 {
				t.Fatalf("block %d syndrome %d = %d", j, i, s)
			}
			alpha = gfMul(alpha, 2)
		}
		data = append(data, blk[:len(blk)-ecLen]...)
	}
	// Parse byte mode.
	var br bitBuffer
	br.bytes = data
	pos := 0
	read := func(nbits int) int {
		v := 0
		for i := 0; i < nbits; i++ {
			v <<= 1
			if data[pos/8]&(0x80>>uint(pos%8)) != 0 {
				v |= 1
			}
			pos++
		}
		return v
	}
	if mode := read(4); mode != 4 {
		t.Fatalf("mode %d", mode)
	}
	ccBits := 8
	if c.Version >= 10 {
		ccBits = 16
	}
	count := read(ccBits)
	out := make([]byte, count)
	for i := range out {
		out[i] = byte(read(8))
	}
	return out
}

func payloadOfLen(n int) []byte {
	b := make([]byte, n)
	for i := range b {
		b[i] = byte('A' + (i*7)%26)
	}
	return b
}

func TestRoundTripAllVersionsAndMasks(t *testing.T) {
	for v := 1; v <= MaxVersion; v++ {
		for _, n := range []int{Capacity(v), Capacity(v) - 1} {
			if v > 1 && n <= Capacity(v-1) {
				continue
			}
			p := payloadOfLen(n)
			for m := -1; m < 8; m++ {
				c, err := EncodeMask(p, m)
				if err != nil {
					t.Fatal(err)
				}
				if c.Version != v {
					t.Fatalf("len %d: version %d, want %d", n, c.Version, v)
				}
				if got := decode(t, c); !bytes.Equal(got, p) {
					t.Fatalf("v%d mask %d: decoded %q", v, m, got)
				}
			}
		}
	}
}

func TestFunctionPatternsAndVersionInfo(t *testing.T) {
	c, err := Encode(payloadOfLen(150)) // version 8
	if err != nil {
		t.Fatal(err)
	}
	if c.Version != 8 || c.Size != 49 {
		t.Fatalf("v%d size %d", c.Version, c.Size)
	}
	// Finder centre and its ring.
	if !c.Dark(3, 3) || c.Dark(1, 3) || !c.Dark(0, 0) || c.Dark(7, 0) {
		t.Error("top-left finder wrong")
	}
	// Timing pattern.
	for i := 8; i < c.Size-8; i++ {
		if c.Dark(i, 6) != (i%2 == 0) || c.Dark(6, i) != (i%2 == 0) {
			t.Fatalf("timing wrong at %d", i)
		}
	}
	// Version info, both copies.
	vb := versionBits(8)
	for i := 0; i < 18; i++ {
		a, b := c.Size-11+i%3, i/3
		if c.Dark(a, b) != bit(vb, i) || c.Dark(b, a) != bit(vb, i) {
			t.Fatalf("version bit %d wrong", i)
		}
	}
	// An alignment pattern centre (24,24) is dark with a light ring.
	if !c.Dark(24, 24) || c.Dark(23, 24) || !c.Dark(22, 24) {
		t.Error("alignment pattern wrong")
	}
}

func TestRenderers(t *testing.T) {
	c, err := Encode([]byte("gs2:test"))
	if err != nil {
		t.Fatal(err)
	}
	svg := c.SVG()
	if !strings.HasPrefix(svg, "<svg ") || !strings.Contains(svg, `fill="#ffffff"`) || !strings.HasSuffix(svg, "</svg>") {
		t.Errorf("svg: %.80s", svg)
	}
	total := c.Size + 2*QuietZone
	if !strings.Contains(svg, fmt.Sprintf(`viewBox="0 0 %d %d"`, total, total)) {
		t.Error("svg viewBox does not include the quiet zone")
	}
	for _, ansi := range []bool{true, false} {
		lines := strings.Split(strings.TrimSuffix(c.Terminal(ansi), "\n"), "\n")
		if len(lines) != (total+1)/2 {
			t.Errorf("ansi=%v: %d lines", ansi, len(lines))
		}
	}
	rows := c.Rows()
	if len(rows) != c.Size || len(rows[0]) != c.Size || strings.Trim(rows[0], "01") != "" {
		t.Error("rows malformed")
	}
}

type fixture struct {
	file    string
	payload string
}

func fixtures(t *testing.T) []fixture {
	b, err := os.ReadFile("../../testdata/gs2-vectors.json")
	if err != nil {
		t.Fatal(err)
	}
	var v struct {
		QR string `json:"qr"`
	}
	if err := json.Unmarshal(b, &v); err != nil {
		t.Fatal(err)
	}
	return []fixture{
		{"gs2-vector.txt", v.QR},
		{"gs2-example2.txt", "gs2:Zm9vYmFyYmF6cXV4:_-_-0123456789abcdefghijklmnopqrstuvwxyzABCDE"},
		{"short-v1.txt", "gs2:hello"},
		{"v6-100.txt", "gs2:" + string(payloadOfLen(96))},
		{"v8-150.txt", "gs2:" + string(payloadOfLen(146))},
		{"v10-213.txt", "gs2:" + string(payloadOfLen(209))},
	}
}

// TestFixtures checks (or with -update writes) testdata/qr-fixtures/*.txt:
// line 1 is the payload, the following lines are the module rows ('1' dark), no quiet zone.
func TestFixtures(t *testing.T) {
	dir := filepath.Join("..", "..", "testdata", "qr-fixtures")
	for _, f := range fixtures(t) {
		c, err := Encode([]byte(f.payload))
		if err != nil {
			t.Fatal(err)
		}
		if got := decode(t, c); string(got) != f.payload {
			t.Fatalf("%s: self-decode mismatch", f.file)
		}
		content := f.payload + "\n" + strings.Join(c.Rows(), "\n") + "\n"
		path := filepath.Join(dir, f.file)
		if *update {
			if err := os.MkdirAll(dir, 0o755); err != nil {
				t.Fatal(err)
			}
			if err := os.WriteFile(path, []byte(content), 0o644); err != nil {
				t.Fatal(err)
			}
			continue
		}
		old, err := os.ReadFile(path)
		if err != nil {
			t.Fatalf("%v (run go test ./internal/qr -update)", err)
		}
		if string(old) != content {
			t.Errorf("%s differs from the encoder output", f.file)
		}
	}
}
