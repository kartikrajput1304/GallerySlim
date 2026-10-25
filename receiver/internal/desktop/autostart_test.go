package desktop

import (
	"encoding/binary"
	"encoding/xml"
	"os"
	"strings"
	"testing"
)

func TestWindowsRunValue(t *testing.T) {
	got, err := windowsRunValue(`C:\Users\Kartik R\AppData\Local\Programs\GallerySlim Receiver\GallerySlim-Receiver.exe`)
	want := `"C:\Users\Kartik R\AppData\Local\Programs\GallerySlim Receiver\GallerySlim-Receiver.exe" --background`
	if err != nil || got != want {
		t.Errorf("%q %v", got, err)
	}
	for _, bad := range []string{"", `C:\a"b.exe`, "C:\\a\nb.exe"} {
		if _, err := windowsRunValue(bad); err == nil {
			t.Errorf("%q accepted", bad)
		}
	}
}

func TestLaunchAgentPlist(t *testing.T) {
	exe := "/Users/k/Applications/GallerySlim Receiver.app/Contents/MacOS/GallerySlim-Receiver & <x>"
	p, err := launchAgentPlist(exe)
	if err != nil {
		t.Fatal(err)
	}
	// Well-formed XML whose ProgramArguments are the exe and --background.
	dec := xml.NewDecoder(strings.NewReader(p))
	dec.Strict = false
	var strs []string
	inArray, inString := false, false
	for {
		tok, err := dec.Token()
		if err != nil {
			break
		}
		switch v := tok.(type) {
		case xml.StartElement:
			inArray = inArray || v.Name.Local == "array"
			inString = v.Name.Local == "string"
		case xml.EndElement:
			if v.Name.Local == "array" {
				inArray = false
			}
			inString = false
		case xml.CharData:
			if inArray && inString {
				strs = append(strs, string(v))
			}
		}
	}
	if len(strs) != 2 || strs[0] != exe || strs[1] != "--background" {
		t.Errorf("arguments %q", strs)
	}
	for _, k := range []string{"<key>RunAtLoad</key><true/>", "<key>Label</key><string>com.galleryslim.receiver</string>", "<key>KeepAlive</key><false/>"} {
		if !strings.Contains(p, k) {
			t.Errorf("missing %s", k)
		}
	}
	if _, err := launchAgentPlist("/a\x00b"); err == nil {
		t.Error("control character accepted")
	}
}

func TestDesktopEntry(t *testing.T) {
	got, err := desktopExecArg(`/home/k/My "Apps"/$HOME/100%/a\b` + "`x`")
	want := `"/home/k/My \\"Apps\\"/\\$HOME/100%%/a\\\\b\\` + "`x\\\\`" + `"`
	if err != nil || got != want {
		t.Errorf("got  %s\nwant %s", got, want)
	}
	e, _ := desktopEntry("/home/k/.local/bin/gallery-slim-receiver", true)
	for _, line := range []string{"[Desktop Entry]", "Type=Application", `Exec="/home/k/.local/bin/gallery-slim-receiver" --background`, "X-GNOME-Autostart-enabled=true", "Terminal=false"} {
		if !strings.Contains(e, line+"\n") {
			t.Errorf("autostart entry lacks %q:\n%s", line, e)
		}
	}
	if !autostartEnabled(e) {
		t.Error("own entry not enabled")
	}
	e, _ = desktopEntry("/opt/x", false)
	if strings.Contains(e, "--background") || !strings.Contains(e, "Categories=") {
		t.Errorf("menu entry:\n%s", e)
	}
	if autostartEnabled(e+"Hidden=true\n") || autostartEnabled("garbage") {
		t.Error("hidden or garbage entry counted as enabled")
	}
	if _, err := desktopEntry("/a\nExec=evil", true); err == nil {
		t.Error("newline accepted")
	}
}

func TestMacBundleRoot(t *testing.T) {
	if r, ok := macBundleRoot("/Users/k/Downloads/GallerySlim Receiver.app/Contents/MacOS/GallerySlim-Receiver"); !ok || r != "/Users/k/Downloads/GallerySlim Receiver.app" {
		t.Errorf("%q %v", r, ok)
	}
	if _, ok := macBundleRoot("/usr/local/bin/GallerySlim-Receiver"); ok {
		t.Error("bare binary taken as bundle")
	}
}

func TestTrayIcon(t *testing.T) {
	ico, err := os.ReadFile("tray.ico")
	if err != nil {
		t.Fatal(err)
	}
	for size, want := range map[int]int{16: 16, 20: 24, 32: 32, 48: 32} {
		b, err := icoEntry(ico, size)
		if err != nil {
			t.Fatal(err)
		}
		// A 32-bit BMP: BITMAPINFOHEADER with doubled height, then pixels and the AND mask.
		w := int(binary.LittleEndian.Uint32(b[4:]))
		h := int(binary.LittleEndian.Uint32(b[8:]))
		bpp := binary.LittleEndian.Uint16(b[14:])
		if binary.LittleEndian.Uint32(b) != 40 || w != want || h != 2*want || bpp != 32 || len(b) != 40+w*w*4+((w+31)/32)*4*w {
			t.Errorf("size %d: got %dx%d %dbpp len %d", size, w, h, bpp, len(b))
		}
	}
	for _, bad := range [][]byte{nil, {0, 0, 2, 0, 1, 0}, append([]byte{0, 0, 1, 0, 1, 0}, make([]byte, 16)...)} {
		if _, err := icoEntry(bad, 16); err == nil {
			t.Errorf("bad icon accepted: %v", bad)
		}
	}
}

func TestTrayTip(t *testing.T) {
	if got := trayTip("GallerySlim Receiver \u2014 ready\n"); string(utf16Decode(got)) != "GallerySlim Receiver \u2014 ready " {
		t.Errorf("%q", string(utf16Decode(got)))
	}
	long := strings.Repeat("😀", 100) // 2 units each: 63 fit
	if got := trayTip(long); len(got) != 126 {
		t.Errorf("len %d", len(got))
	}
}

func utf16Decode(u []uint16) []rune {
	var out []rune
	for i := 0; i < len(u); i++ {
		r := rune(u[i])
		if r >= 0xD800 && r < 0xDC00 && i+1 < len(u) {
			r = 0x10000 + (r-0xD800)<<10 + rune(u[i+1]) - 0xDC00
			i++
		}
		out = append(out, r)
	}
	return out
}
