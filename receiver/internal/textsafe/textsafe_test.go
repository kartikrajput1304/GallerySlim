package textsafe

import "testing"

func TestClean(t *testing.T) {
	cases := []struct {
		in   string
		max  int
		want string
	}{
		{"Pixel 8", 40, "Pixel 8"},
		{"  Galaxy\tS24\n Ultra ", 40, "Galaxy S24 Ultra"},
		{"evil\x1b[31mred\x07", 40, "evil[31mred"},
		{"photo‮gpj.exe", 40, "photogpj.exe"},
		{"a⁦b⁩c‏d؜e", 40, "abcde"},
		{"bad\xff\xfeutf8", 40, "badutf8"},
		{"<script>alert(1)</script>", 40, "<script>alert(1)</script>"}, // escaping happens at display time
		{"ÄÖÜ写真📷 phone name that is far too long to show", 10, "ÄÖÜ写真📷 pho"},
		{"line sep", 40, "linesep"},
	}
	for _, c := range cases {
		if got := Clean(c.in, c.max); got != c.want {
			t.Errorf("Clean(%q, %d) = %q, want %q", c.in, c.max, got, c.want)
		}
	}
}
