package receive

// §9 remembered pairing against the fake phone: QR pairing stores PK on both sides; a later
// run claims a known session and is approved without a QR; a wrong proof or a forgotten
// receiver falls back to the QR (or fails with ErrNotRemembered when NoQR is set).

import (
	"bytes"
	"context"
	"errors"
	"testing"
	"time"

	"galleryslim/receiver/internal/client"
	"galleryslim/receiver/internal/store"
)

const testRID = "EBESExQVFhcYGRobHB0eHw"

type pairRun struct {
	paired   Paired
	err      error
	qrShown  int
	claimed  int
	rejected int
}

func pairWith(t *testing.T, p *phone, pk []byte, noQR bool) pairRun {
	t.Helper()
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	var r pairRun
	r.paired, r.err = PairRemembered(ctx, PairOptions{
		HTTP: client.NewHTTPClient(), Addr: p.addr(), Config: client.Config{FrameSize: p.frameSize},
		Info:      client.SessionInfo{Receiver: "test-laptop", OS: "linux", Folder: "Photos", RID: testRID},
		PollEvery: 5 * time.Millisecond,
		PairKey:   pk, NoQR: noQR,
		ShowQR: func(text, _ string) {
			r.qrShown++
			go p.approve(text)
		},
		OnClaimed:  func() { r.claimed++ },
		OnRejected: func() { r.rejected++ },
	})
	return r
}

// receiveWith downloads everything with an approved session and returns the summary.
func receiveWith(t *testing.T, p *phone, s *client.Session) Summary {
	t.Helper()
	folder, err := store.Open(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	sum, err := Run(context.Background(), Options{Session: s, Folder: folder, Phone: p.name, Events: &recorder{}, RetryWait: 5 * time.Millisecond})
	if err != nil {
		t.Fatal(err)
	}
	return sum
}

func TestRememberedPairingAccepted(t *testing.T) {
	p := newPhone(t, testFrame, photo("1", "a.jpg", payload(1, 3000)))
	first := pairWith(t, p, nil, false)
	if first.err != nil || first.qrShown != 1 || first.paired.Remembered {
		t.Fatalf("first pairing %+v", first)
	}
	pk := first.paired.PairKey
	if len(pk) != 32 || !bytes.Equal(pk, p.paired[testRID]) {
		t.Fatalf("PK not shared: receiver %x phone %x", pk, p.paired[testRID])
	}
	if sum := receiveWith(t, p, first.paired.Session); sum.Verified != 1 {
		t.Fatalf("first run %+v", sum)
	}

	// Later: no QR; the owner approves the claimed session.
	p.onClaimed = p.approveKnown
	second := pairWith(t, p, pk, true)
	if second.err != nil || second.qrShown != 0 || second.claimed != 1 || !second.paired.Remembered || second.paired.PairKey != nil {
		t.Fatalf("remembered pairing %+v", second)
	}
	if got := p.claimLog(); len(got) != 1 || got[0] != "ok" {
		t.Errorf("claims %v", got)
	}
	// Keys derived from PK work for the signed and encrypted API.
	if sum := receiveWith(t, p, second.paired.Session); sum.Verified+sum.Already != 1 {
		t.Fatalf("remembered run %+v", sum)
	}
}

func TestRememberedPairingWrongProofFallsBackToQR(t *testing.T) {
	p := newPhone(t, testFrame)
	if r := pairWith(t, p, nil, false); r.err != nil {
		t.Fatal(r.err)
	}
	wrong := bytes.Repeat([]byte{7}, 32)
	r := pairWith(t, p, wrong, false)
	if r.err != nil || r.rejected != 1 || r.claimed != 0 || r.qrShown != 1 || r.paired.Remembered {
		t.Fatalf("fallback %+v", r)
	}
	if got := p.claimLog(); len(got) != 1 || got[0] != "401" {
		t.Errorf("claims %v", got)
	}
	// The QR pairing replaced the phone's PK with the new one.
	if !bytes.Equal(r.paired.PairKey, p.paired[testRID]) {
		t.Error("new PK not stored on the phone")
	}
	// With NoQR (background), a wrong proof is an error and no QR is shown.
	r = pairWith(t, p, wrong, true)
	if !errors.Is(r.err, ErrNotRemembered) || r.qrShown != 0 {
		t.Fatalf("NoQR %+v", r)
	}
}

func TestRememberedPairingForgottenFallsBackToQR(t *testing.T) {
	p := newPhone(t, testFrame)
	first := pairWith(t, p, nil, false)
	if first.err != nil {
		t.Fatal(first.err)
	}
	p.forget()
	// The phone no longer says "known", so no claim is sent at all.
	r := pairWith(t, p, first.paired.PairKey, false)
	if r.err != nil || r.rejected != 1 || r.qrShown != 1 || len(p.claimLog()) != 0 {
		t.Fatalf("forgotten %+v claims %v", r, p.claimLog())
	}
	// A phone that forgot us but still says known (a stale answer) rejects the claim with 401.
	p.forget()
	p.mu.Lock()
	p.paired[testRID] = bytes.Repeat([]byte{9}, 32)
	p.mu.Unlock()
	r = pairWith(t, p, first.paired.PairKey, true)
	if !errors.Is(r.err, ErrNotRemembered) || len(p.claimLog()) != 1 {
		t.Fatalf("stale known %+v claims %v", r, p.claimLog())
	}
}

func TestRememberedPairingOlderPhone(t *testing.T) {
	p := newPhone(t, testFrame)
	p.noClaim = true
	p.paired[testRID] = bytes.Repeat([]byte{1}, 32) // says known, but has no claim route
	r := pairWith(t, p, bytes.Repeat([]byte{1}, 32), false)
	if r.err != nil || r.qrShown != 1 {
		t.Fatalf("older phone %+v", r)
	}
}

func TestNoRIDNoPairKey(t *testing.T) {
	p := newPhone(t, testFrame)
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	got, err := PairRemembered(ctx, PairOptions{
		HTTP: client.NewHTTPClient(), Addr: p.addr(), Info: client.SessionInfo{OS: "linux"},
		PollEvery: 5 * time.Millisecond, ShowQR: func(text, _ string) { go p.approve(text) },
	})
	if err != nil || got.PairKey != nil || len(p.paired) != 0 {
		t.Fatalf("without rid: %+v %v phone %d", got, err, len(p.paired))
	}
}

func TestRememberedPairingDeclined(t *testing.T) {
	p := newPhone(t, testFrame)
	first := pairWith(t, p, nil, false)
	if first.err != nil {
		t.Fatal(first.err)
	}
	p.onClaimed = func(sid string) { // the owner says no: the phone drops the session
		p.mu.Lock()
		p.sessions[sid].ended = true
		p.mu.Unlock()
	}
	r := pairWith(t, p, first.paired.PairKey, true)
	if !errors.Is(r.err, ErrPairingExpired) || r.claimed != 1 {
		t.Fatalf("declined %+v", r)
	}
}
