package blackbox

// Finding the phone (10.4), cloud detection (10.2), settings and log (10.9), and the shipped
// files (10.1).

import (
	"archive/tar"
	"archive/zip"
	"bufio"
	"compress/gzip"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"net"
	"net/http"
	"net/netip"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"galleryslim/receiver/internal/app"
	"galleryslim/receiver/internal/applog"
	"galleryslim/receiver/internal/desktop"
	"galleryslim/receiver/internal/discover"
)

// RN-02 / RN-07: every address of /24–/30 networks is probed, excluding network, broadcast
// and the computer's own address; wider (/16) and /31–/32 are not.
func TestRN02_SubnetHosts(t *testing.T) {
	pf := func(s string) netip.Prefix { return netip.MustParsePrefix(s) }
	hosts := discover.SubnetHosts([]netip.Prefix{pf("192.168.1.10/24")})
	if len(hosts) != 253 {
		t.Errorf("/24 gives %d addresses, want 253 (254 hosts minus this computer)", len(hosts))
	}
	for _, h := range hosts {
		s := h.String()
		if s == "192.168.1.0" || s == "192.168.1.255" || s == "192.168.1.10" {
			t.Errorf("%s should not be probed", s)
		}
	}
	if h := discover.SubnetHosts([]netip.Prefix{pf("10.0.0.1/30")}); len(h) != 1 || h[0].String() != "10.0.0.2" {
		t.Errorf("/30 gives %v, want [10.0.0.2]", h)
	}
	for _, p := range []string{"10.1.2.3/16", "192.168.0.5/23", "10.0.0.1/31", "10.0.0.1/32"} {
		if h := discover.SubnetHosts([]netip.Prefix{pf(p)}); len(h) != 0 {
			t.Errorf("%s gives %d addresses, want none", p, len(h))
		}
	}
	if len(discover.Ports) != 5 || discover.Ports[0] != 8765 || discover.Ports[4] != 8769 {
		t.Errorf("phone ports %v, want 8765–8769", discover.Ports)
	}
}

// RN-03: a phone counts only if it answers as GallerySlim protocol 2.
func TestRN03_OnlyProtocol2Counts(t *testing.T) {
	serve := func(hdr string, body string) string {
		ln, _ := net.Listen("tcp4", "127.0.0.1:0")
		srv := &http.Server{Handler: http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			if hdr != "" {
				w.Header().Set("X-GallerySlim", hdr)
			}
			w.Write([]byte(body))
		})}
		go srv.Serve(ln)
		t.Cleanup(func() { srv.Close() })
		return ln.Addr().String()
	}
	ctx := ctxFor(t, 20*time.Second)
	prober := discover.NewProber()
	good := serve("2", `{"app":"GallerySlim","proto":2,"name":"Pixel","code":"AB-12","port":1}`)
	c, err := discover.ProbeAddress(ctx, good, prober)
	if err != nil || c.Name != "Pixel" || c.Code != "AB-12" {
		t.Errorf("protocol-2 phone not found: %+v %v", c, err)
	}
	for name, addr := range map[string]string{
		"protocol 1":  serve("2", `{"app":"GallerySlim","proto":1,"name":"Old","code":"AB-12"}`),
		"no header":   serve("", `{"app":"GallerySlim","proto":2,"name":"X","code":"AB-12"}`),
		"other app":   serve("2", `{"app":"Other","proto":2,"name":"X","code":"AB-12"}`),
		"not json":    serve("2", `<html>router</html>`),
		"header only": serve("1", `{"app":"GallerySlim","proto":2,"name":"X","code":"AB-12"}`),
	} {
		if _, err := discover.ProbeAddress(ctx, addr, prober); err == nil {
			t.Errorf("%s counted as a phone", name)
		}
	}
}

// RN-04: a typed address without a port is tried on 8765–8769 (live probe on 127.0.0.1:8767).
func TestRN04_TypedAddressFindsPhoneOnOtherPort(t *testing.T) {
	ln, err := net.Listen("tcp4", "127.0.0.1:8767")
	if err != nil {
		t.Skip("port 8767 busy:", err)
	}
	p := startFakePhoneOn(t, ln)
	c, err := discover.ProbeAddress(ctxFor(t, 20*time.Second), "127.0.0.1", discover.NewProber())
	if err != nil || c.Addr != "127.0.0.1:8767" || c.Name != p.Name {
		t.Errorf("found %+v, %v; want the phone at 127.0.0.1:8767", c, err)
	}
}

// RD-05: cloud detection on Linux.
func TestRD05_CloudDetection(t *testing.T) {
	home := t.TempDir()
	t.Setenv("HOME", home)
	synced := map[string]string{
		"/data/Dropbox/Photos":              "Dropbox",
		"/data/Dropbox (Personal)/Photos":   "Dropbox",
		"/data/OneDrive/Pictures":           "OneDrive",
		"/data/OneDrive - Contoso/Pictures": "OneDrive",
		"/data/Google Drive/Photos":         "Google Drive",
		"/data/My Drive/Photos":             "Google Drive",
		"/data/iCloudDrive/Photos":          "iCloud",
		"/data/iCloud Drive/Photos":         "iCloud",
		"/data/iCloud Photos/x":             "iCloud",
		filepath.Join(home, "Insync", "me@example.com", "Photos"): "Insync",
	}
	for p, prov := range synced {
		ok, got := desktop.CloudSynced(p)
		if !ok {
			t.Errorf("%s not detected as synced", p)
		} else if !strings.Contains(got, prov) {
			t.Errorf("%s: provider %q, want it to name %s", p, got, prov)
		}
	}
	for _, p := range []string{"/data/pCloudDrive/Photos", "/data/Nextcloud/Photos", "/data/MEGA/Photos", "/data/Pictures", "/data/MyDropboxBackup/x", "/data/Dropboxes/x"} {
		if ok, prov := desktop.CloudSynced(p); ok {
			t.Errorf("%s detected as synced (%s); PDF: other services/names are not detected", p, prov)
		}
	}
}

// RS-02: config.json holds the folder (absolute only) and openBrowser (default true); written
// atomically.
func TestRS02_ConfigFile(t *testing.T) {
	dir := t.TempDir()
	if c := app.LoadConfig(dir); c.Folder != "" || !c.WantsBrowser() {
		t.Errorf("defaults: %+v", c)
	}
	if err := app.SaveConfig(dir, app.Config{Folder: "/srv/photos"}); err != nil {
		t.Fatal(err)
	}
	entries, _ := os.ReadDir(dir)
	if len(entries) != 1 || entries[0].Name() != "config.json" {
		var names []string
		for _, e := range entries {
			names = append(names, e.Name())
		}
		t.Errorf("settings folder holds %v; a leftover temp file means the write was not atomic", names)
	}
	if c := app.LoadConfig(dir); c.Folder != "/srv/photos" || !c.WantsBrowser() {
		t.Errorf("round trip: %+v", c)
	}
	os.WriteFile(filepath.Join(dir, "config.json"), []byte(`{"folder":"Pictures/Phone","openBrowser":false}`), 0o600)
	if c := app.LoadConfig(dir); c.Folder != "" || c.WantsBrowser() {
		t.Errorf("relative folder must be ignored, openBrowser false kept: %+v", c)
	}
	b, _ := os.ReadFile(filepath.Join(dir, "config.json"))
	var m map[string]any
	_ = json.Unmarshal(b, &m)
}

// RS-03: receiver.log is private and rotates at 2 MB to receiver.log.1.
func TestRS03_LogRotation(t *testing.T) {
	dir := t.TempDir()
	log := applog.Open(dir)
	line := strings.Repeat("x", 1000)
	for i := 0; i < 2600; i++ {
		log.Info("filler", "n", i, "pad", line)
	}
	main := filepath.Join(dir, "receiver.log")
	old := filepath.Join(dir, "receiver.log.1")
	fi, err := os.Stat(main)
	if err != nil {
		t.Fatal(err)
	}
	oi, err := os.Stat(old)
	if err != nil {
		t.Fatalf("no receiver.log.1 after writing over 2 MB: %v", err)
	}
	if oi.Size() > 2<<20 || fi.Size() > 2<<20 {
		t.Errorf("log sizes %d / %d exceed 2 MB", fi.Size(), oi.Size())
	}
	if fi.Mode().Perm()&0o077 != 0 {
		t.Errorf("receiver.log mode %v, want private", fi.Mode().Perm())
	}
	if oi.Mode().Perm()&0o077 != 0 {
		t.Errorf("receiver.log.1 mode %v, want private", oi.Mode().Perm())
	}
}

// RF-01: the shipped files and SHA256SUMS (10.1).
func TestRF01_ShippedFiles(t *testing.T) {
	dist := filepath.Join("..", "..", "dist", "receiver")
	if !fileExists(dist) {
		t.Skip("dist/receiver not built")
	}
	want := []string{
		"windows-amd64/GallerySlim-Receiver.exe", "windows-arm64/GallerySlim-Receiver.exe",
		"GallerySlim-Receiver-macos-arm64.zip", "GallerySlim-Receiver-macos-amd64.zip",
		"GallerySlim-Receiver-linux-amd64.tar.gz", "GallerySlim-Receiver-linux-arm64.tar.gz",
	}
	sums := map[string]string{}
	f, err := os.Open(filepath.Join(dist, "SHA256SUMS"))
	if err != nil {
		t.Fatalf("SHA256SUMS: %v", err)
	}
	sc := bufio.NewScanner(f)
	for sc.Scan() {
		fs := strings.Fields(sc.Text())
		if len(fs) == 2 {
			sums[strings.TrimPrefix(fs[1], "*")] = fs[0]
		}
	}
	f.Close()
	for _, w := range want {
		p := filepath.Join(dist, filepath.FromSlash(w))
		b, err := os.ReadFile(p)
		if err != nil {
			t.Errorf("missing %s", w)
			continue
		}
		h := sha256.Sum256(b)
		if sums[w] == "" {
			t.Errorf("SHA256SUMS does not list %s", w)
		} else if sums[w] != hex.EncodeToString(h[:]) {
			t.Errorf("checksum of %s does not match SHA256SUMS", w)
		}
	}
	for name, sum := range sums {
		b, err := os.ReadFile(filepath.Join(dist, filepath.FromSlash(name)))
		if err != nil {
			t.Errorf("SHA256SUMS lists %s, which is missing", name)
			continue
		}
		if h := sha256.Sum256(b); hex.EncodeToString(h[:]) != sum {
			t.Errorf("checksum of %s does not match", name)
		}
	}
	// Linux: ./GallerySlim-Receiver inside the archive; macOS: "GallerySlim Receiver" app.
	for _, arch := range []string{"amd64", "arm64"} {
		gzf, err := os.Open(filepath.Join(dist, "GallerySlim-Receiver-linux-"+arch+".tar.gz"))
		if err != nil {
			continue
		}
		zr, err := gzip.NewReader(gzf)
		if err != nil {
			t.Errorf("linux %s: %v", arch, err)
			gzf.Close()
			continue
		}
		tr := tar.NewReader(zr)
		found := false
		for {
			h, err := tr.Next()
			if err != nil {
				break
			}
			if filepath.Base(h.Name) == "GallerySlim-Receiver" && h.FileInfo().Mode()&0o111 != 0 {
				found = true
			}
		}
		gzf.Close()
		if !found {
			t.Errorf("linux %s archive has no executable GallerySlim-Receiver", arch)
		}
		zf, err := zip.OpenReader(filepath.Join(dist, "GallerySlim-Receiver-macos-"+arch+".zip"))
		if err != nil {
			t.Errorf("macos %s: %v", arch, err)
			continue
		}
		app := false
		for _, e := range zf.File {
			if strings.HasPrefix(e.Name, "GallerySlim Receiver.app/") {
				app = true
			}
		}
		zf.Close()
		if !app {
			t.Errorf("macos %s zip has no \"GallerySlim Receiver.app\"", arch)
		}
	}
}
