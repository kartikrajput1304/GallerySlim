package blackbox

// The page server driven in-process with made-up snapshots, to check what reaches the
// browser: PDF 10.3 safety notes and 10.9 text limits (page 84).

import (
	"encoding/json"
	"io"
	"net/http"
	"strings"
	"sync"
	"testing"
	"time"
	"unicode/utf8"

	"galleryslim/receiver/internal/ui"
)

func startUI(t *testing.T, h ui.Handlers) (base string, port string) {
	t.Helper()
	url, stop, err := ui.Start(h)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(stop)
	m := pageURL.FindStringSubmatch(url)
	if m == nil {
		t.Fatalf("odd page address %q", url)
	}
	return url, m[1]
}

func getJSON(t *testing.T, url string) map[string]any {
	t.Helper()
	r, err := plainClient.Get(url)
	if err != nil {
		t.Fatal(err)
	}
	defer r.Body.Close()
	b, _ := io.ReadAll(r.Body)
	var m map[string]any
	if err := json.Unmarshal(b, &m); err != nil {
		t.Fatalf("%s: %v", b, err)
	}
	return m
}

func postJSON(t *testing.T, base, port, name, body string) map[string]any {
	t.Helper()
	req, _ := http.NewRequest("POST", base+name, strings.NewReader(body))
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Origin", "http://127.0.0.1:"+port)
	r, err := plainClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	defer r.Body.Close()
	b, _ := io.ReadAll(r.Body)
	var m map[string]any
	_ = json.Unmarshal(b, &m)
	return m
}

const dirty = "‮\u0007evil‏\x1b[31m"

// RS-14 / RP-17: text from the phone is cleaned of control and direction characters and
// limited: phone name 40, code 12, file name 200, detail 300, errors 400; at most 10 phones
// and 500 rows (then "and N earlier file(s)").
func TestRS14_TextLimits(t *testing.T) {
	long := func(n int) string { return strings.Repeat("ह", n) } // Hindi letter, 3 bytes
	snap := ui.Snapshot{
		Phase:     "finding",
		PhoneName: dirty + long(100),
		Code:      dirty + long(50),
		Current:   long(300),
		Error:     dirty + long(600),
		Version:   receiverVersion,
	}
	for i := 0; i < 15; i++ {
		snap.Phones = append(snap.Phones, ui.PhoneChoice{Name: dirty + long(80), Code: "AB-12", Addr: "192.168.1.20:8765"})
	}
	for i := 0; i < 620; i++ {
		snap.Rows = append(snap.Rows, ui.FileRow{Name: dirty + long(400), Size: 10, State: "verified", Detail: dirty + long(500)})
	}
	base, _ := startUI(t, ui.Handlers{Snapshot: func() ui.Snapshot { return snap }})
	st := getJSON(t, base+"status.json")
	check := func(what string, v any, max int) {
		s, _ := v.(string)
		if n := utf8.RuneCountInString(s); n > max {
			t.Errorf("%s has %d characters, limit %d", what, n, max)
		}
		if strings.ContainsAny(s, "‮\u0007‏\x1b") {
			t.Errorf("%s still has control/direction characters: %q", what, s[:min(len(s), 20)])
		}
		if s == "" {
			t.Errorf("%s was dropped entirely", what)
		}
	}
	check("phone name", st["phoneName"], 40)
	check("code", st["code"], 12)
	check("error", st["error"], 400)
	phones, _ := st["phones"].([]any)
	if len(phones) > 10 {
		t.Errorf("%d phones shown, limit 10", len(phones))
	}
	for _, p := range phones {
		check("phone button name", p.(map[string]any)["name"], 40)
	}
	rows, _ := st["rows"].([]any)
	if len(rows) != 500 {
		t.Errorf("%d rows sent, want 500", len(rows))
	}
	if st["rowsHidden"] != float64(120) {
		t.Errorf("rowsHidden %v, want 120 (for \"and N earlier file(s)\")", st["rowsHidden"])
	}
	for _, r := range rows[:3] {
		m := r.(map[string]any)
		check("file name", m["name"], 200)
		check("detail", m["detail"], 300)
	}
}

// RP-15: general error texts.
func TestRP15_GeneralErrors(t *testing.T) {
	block := make(chan struct{})
	var once sync.Once
	h := ui.Handlers{
		Snapshot: func() ui.Snapshot { return ui.Snapshot{Phase: "finding"} },
		Retry: func() error {
			<-block
			return nil
		},
		OpenFolder: func() error { panic("boom") },
	}
	base, port := startUI(t, h)
	t.Cleanup(func() { once.Do(func() { close(block) }) })
	if m := postJSON(t, base, port, "choose-folder", "{}"); m["error"] != "This isn't available right now." {
		t.Errorf("missing handler → %v", m)
	}
	if m := postJSON(t, base, port, "open-folder", "{}"); m["error"] != "Something went wrong inside the receiver. Please try again." {
		t.Errorf("crashing handler → %v", m)
	}
	done := make(chan map[string]any, 1)
	go func() { done <- postJSON(t, base, port, "retry", "{}") }()
	time.Sleep(300 * time.Millisecond)
	if m := postJSON(t, base, port, "address", `{"address":"192.168.1.2:8765"}`); m["error"] != "Please wait a moment, the receiver is still working on your last request." {
		t.Errorf("second action while busy → %v", m)
	}
	once.Do(func() { close(block) })
	<-done
}
