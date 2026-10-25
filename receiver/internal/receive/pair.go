// Package receive runs a receiving session: pairing with the phone, then downloading,
// saving, re-checking on disk and acknowledging every file.
//
// Security-sensitive: needs a person's review before release.
package receive

import (
	"context"
	"crypto/rand"
	"errors"
	"net/http"
	"time"

	"galleryslim/receiver/internal/client"
	"galleryslim/receiver/internal/gs2"
)

// ErrPairingExpired means the phone dropped the pairing request (not scanned in time, or
// declined).
var ErrPairingExpired = errors.New("the code expired before the phone approved it")

// ErrNotRemembered means a remembered pairing (§9) was not accepted by the phone (it forgot
// this computer, or the proof was wrong) and PairOptions.NoQR forbade falling back to a QR.
var ErrNotRemembered = errors.New("the phone no longer remembers this computer")

// PairOptions configures Pair.
type PairOptions struct {
	HTTP   *http.Client
	Addr   string // phone ip:port
	Config client.Config
	Info   client.SessionInfo
	// ShowQR is called once with the QR text (gs2:<sid>:<K>) and the short code. The text
	// contains the pairing secret: it may only be drawn as a QR code, never logged or stored.
	ShowQR    func(qrText, code string)
	PollEvery time.Duration // default 1 s
	Timeout   time.Duration // default 10 min (the phone's idle timeout)

	// §9 remembered pairing. Info.RID is this receiver's id. PairKey is the remembered PK for
	// this phone (nil: QR pairing). With a PairKey, Pair asks for a known session and claims
	// it; when the phone does not know this receiver or rejects the proof it falls back to the
	// QR, unless NoQR is set (then it returns ErrNotRemembered).
	PairKey []byte
	NoQR    bool
	// OnClaimed is called when the phone accepted the claim: the owner now confirms on the
	// phone (no QR).
	OnClaimed func()
	// OnRejected is called when a remembered pairing was not accepted (before any fallback).
	OnRejected func()
}

// Paired is the outcome of Pair.
type Paired struct {
	Session *client.Session
	// Remembered is true when the session was approved through a remembered pairing.
	Remembered bool
	// PairKey is the new PK of an approved QR pairing, to be stored for the phone (nil for a
	// remembered session or when no receiver id was sent). Never log or send it.
	PairKey []byte
}

// Pair creates a session, shows the QR and waits until the owner approves it on the phone.
func Pair(ctx context.Context, o PairOptions) (*client.Session, error) {
	p, err := PairRemembered(ctx, o)
	if err != nil {
		return nil, err
	}
	return p.Session, nil
}

// PairRemembered pairs like Pair, using and producing §9 remembered pairings.
func PairRemembered(ctx context.Context, o PairOptions) (Paired, error) {
	if o.PollEvery == 0 {
		o.PollEvery = time.Second
	}
	if o.Timeout == 0 {
		o.Timeout = 10 * time.Minute
	}
	remembered := len(o.PairKey) == gs2.KeySize && gs2.ValidID(o.Info.RID)
	if !remembered && o.NoQR {
		return Paired{}, ErrNotRemembered
	}
	s, err := client.StartSession(ctx, o.HTTP, o.Addr, o.Config, o.Info)
	if err != nil {
		return Paired{}, err
	}
	if remembered {
		claimed := false
		if s.Known() {
			cctx, cancel := context.WithTimeout(ctx, 10*time.Second)
			err := s.Claim(cctx, o.Info.RID, o.PairKey)
			cancel()
			switch {
			case err == nil:
				claimed = true
			case errors.Is(err, client.ErrAuth):
			default:
				return Paired{}, err // network trouble: not a reason to forget anything
			}
		}
		if claimed {
			if err := s.UsePairKey(o.PairKey); err != nil {
				return Paired{}, err
			}
			if o.OnClaimed != nil {
				o.OnClaimed()
			}
			if err := waitApproved(ctx, s, o); err != nil {
				return Paired{}, err
			}
			return Paired{Session: s, Remembered: true}, nil
		}
		if o.OnRejected != nil {
			o.OnRejected()
		}
		if o.NoQR {
			return Paired{}, ErrNotRemembered
		}
		// Fall back to the QR with a fresh session, in case the phone dropped the one whose
		// claim failed. The phone replaces its PK for this receiver when the QR is approved.
		if s, err = client.StartSession(ctx, o.HTTP, o.Addr, o.Config, o.Info); err != nil {
			return Paired{}, err
		}
	}
	return pairQR(ctx, s, o)
}

func pairQR(ctx context.Context, s *client.Session, o PairOptions) (Paired, error) {
	k := make([]byte, gs2.KeySize)
	if _, err := rand.Read(k); err != nil {
		return Paired{}, err
	}
	if err := s.SetKey(k); err != nil {
		clear(k)
		return Paired{}, err
	}
	text := gs2.QRText(s.Sid(), k)
	clear(k)
	o.ShowQR(text, s.Code())
	if err := waitApproved(ctx, s, o); err != nil {
		return Paired{}, err
	}
	p := Paired{Session: s}
	if gs2.ValidID(o.Info.RID) {
		p.PairKey = s.PairKey()
	}
	return p, nil
}

// waitApproved polls the pairing state (§2.4) until approved, expired or the timeout.
func waitApproved(ctx context.Context, s *client.Session, o PairOptions) error {
	deadline := time.Now().Add(o.Timeout)
	failures := 0
	t := time.NewTicker(o.PollEvery)
	defer t.Stop()
	for {
		select {
		case <-ctx.Done():
			return ctx.Err()
		case <-t.C:
		}
		if time.Now().After(deadline) {
			return ErrPairingExpired
		}
		pctx, cancel := context.WithTimeout(ctx, 5*time.Second)
		state, err := s.State(pctx)
		cancel()
		if err != nil {
			if failures++; failures >= 15 {
				return err
			}
			continue
		}
		failures = 0
		switch state {
		case "approved":
			return nil
		case "expired":
			return ErrPairingExpired
		}
	}
}
