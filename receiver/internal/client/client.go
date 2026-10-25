// Package client talks protocol gs2 to the GallerySlim phone app: hello, pairing, the signed
// receiver API and the encrypted list and file streams.
//
// Security-sensitive: needs a person's review before release.
package client

import (
	"bytes"
	"context"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"sync/atomic"
	"time"
	"unicode/utf8"

	"galleryslim/receiver/internal/gs2"
	"galleryslim/receiver/internal/textsafe"
)

// Errors that change what the receiver does next.
var (
	ErrAuth      = errors.New("the phone did not accept this computer's signature")
	ErrForbidden = errors.New("the phone refused requests from this computer's address")
	ErrExpired   = errors.New("the phone ended the session")
	ErrChanged   = errors.New("the file changed on the phone")
	ErrGone      = errors.New("the file is no longer on the phone")
	ErrBusy      = errors.New("the phone stayed busy")
	ErrNotPhone  = errors.New("not a GallerySlim phone")
	ErrStalled   = errors.New("no data from the phone for too long")
	ErrRange     = errors.New("the phone rejected the resume position")
	// ErrUnsigned is an answer without a valid X-GS-RMac signature (§4a). It is treated like
	// a network error: retried, never acted on.
	ErrUnsigned = errors.New("the phone's answer was not signed correctly")
	// ErrNotReceiving is a signed 403 (§10): the phone's "Bring back" / "Receive from
	// computer" screen is not open, so it takes no files.
	ErrNotReceiving = errors.New("the phone is not receiving files right now")
)

// StatusError is an unexpected HTTP status.
type StatusError struct{ Code int }

func (e *StatusError) Error() string { return fmt.Sprintf("the phone answered HTTP %d", e.Code) }

const (
	maxJSON       = 64 << 10
	maxListBytes  = 64 << 20
	maxFiles      = 200000
	maxFileSize   = 1 << 40
	maxIDBytes    = 256
	maxTextRunes  = 40
	maxFileName   = 1024
	maxFolderName = 60
)

// Config tunes the client; the zero value means protocol defaults.
type Config struct {
	FrameSize   int           // plaintext bytes per frame (protocol: 1 MiB)
	BusyWait    time.Duration // wait after 503 (protocol: 2 s)
	BusyRetries int           // retries after 503 (protocol: 5)
	IdleTimeout time.Duration // abort a response that delivers nothing for this long
}

func (c Config) withDefaults() Config {
	if c.FrameSize == 0 {
		c.FrameSize = gs2.FrameSize
	}
	if c.BusyWait == 0 {
		c.BusyWait = 2 * time.Second
	}
	if c.BusyRetries == 0 {
		c.BusyRetries = 5
	}
	if c.IdleTimeout == 0 {
		c.IdleTimeout = 60 * time.Second
	}
	return c
}

// NewHTTPClient returns an HTTP client for talking to a phone on the local network: no proxy,
// no compression, no redirects.
func NewHTTPClient() *http.Client {
	tr := &http.Transport{
		Proxy:                 nil,
		DialContext:           (&net.Dialer{Timeout: 5 * time.Second, KeepAlive: 30 * time.Second}).DialContext,
		MaxIdleConnsPerHost:   4,
		IdleConnTimeout:       60 * time.Second,
		ResponseHeaderTimeout: 60 * time.Second,
		DisableCompression:    true,
		ForceAttemptHTTP2:     false,
	}
	return &http.Client{
		Transport:     tr,
		CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse },
	}
}

// Hello is the phone's /api/hello answer, with its text already cleaned.
type Hello struct {
	App   string `json:"app"`
	Proto int    `json:"proto"`
	Name  string `json:"name"`
	Code  string `json:"code"`
	Port  int    `json:"port"`
	// Pid is the phone id of §9 ("" when the phone sent none or an invalid one).
	Pid string `json:"pid"`
}

func readJSON(r io.Reader, v any) error {
	b, err := io.ReadAll(io.LimitReader(r, maxJSON+1))
	if err != nil {
		return err
	}
	if len(b) > maxJSON {
		return errors.New("answer too large")
	}
	return json.Unmarshal(b, v)
}

func drain(resp *http.Response) {
	io.Copy(io.Discard, io.LimitReader(resp.Body, maxJSON))
	resp.Body.Close()
}

// GetHello asks addr ("ip:port") whether it is a GallerySlim phone speaking gs2.
func GetHello(ctx context.Context, hc *http.Client, addr string) (Hello, error) {
	var h Hello
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, "http://"+addr+"/api/hello", nil)
	if err != nil {
		return h, err
	}
	resp, err := hc.Do(req)
	if err != nil {
		return h, err
	}
	defer drain(resp)
	if resp.StatusCode != http.StatusOK || resp.Header.Get("X-GallerySlim") != "2" {
		return h, ErrNotPhone
	}
	if err := readJSON(resp.Body, &h); err != nil {
		return h, ErrNotPhone
	}
	if h.App != "GallerySlim" || h.Proto != 2 {
		return h, ErrNotPhone
	}
	h.Name = textsafe.Clean(h.Name, maxTextRunes)
	h.Code = textsafe.Clean(h.Code, maxTextRunes)
	if !gs2.ValidID(h.Pid) {
		h.Pid = ""
	}
	return h, nil
}

// Session is one pairing with the phone.
type Session struct {
	hc   *http.Client
	base string
	cfg  Config

	sid   string
	n     []byte
	code  string
	known bool // §9: the phone remembers this receiver's rid

	pairKey []byte // §9 PK of a QR pairing (derived from K in SetKey)

	keys     gs2.Keys
	haveKeys atomic.Bool
	ctr      atomic.Uint64
}

// SessionInfo is what the receiver tells the phone about itself.
type SessionInfo struct {
	Receiver string // computer name, ≤ 40 characters
	OS       string // windows | mac | linux
	Folder   string // folder name shown on the phone, ≤ 60 characters
	RID      string // §9 receiver id; sent only when valid
}

// StartSession creates a receiver session on the phone at addr ("ip:port").
func StartSession(ctx context.Context, hc *http.Client, addr string, cfg Config, info SessionInfo) (*Session, error) {
	s := &Session{hc: hc, base: "http://" + addr, cfg: cfg.withDefaults()}
	body := map[string]string{
		"receiver": textsafe.Clean(info.Receiver, maxTextRunes),
		"os":       info.OS,
		"folder":   textsafe.Clean(info.Folder, maxFolderName),
	}
	if gs2.ValidID(info.RID) {
		body["rid"] = info.RID
	}
	var buf bytes.Buffer
	enc := json.NewEncoder(&buf)
	enc.SetEscapeHTML(false)
	if err := enc.Encode(body); err != nil {
		return nil, err
	}
	payload := bytes.TrimRight(buf.Bytes(), "\n")
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, s.base+"/api/r/session", bytes.NewReader(payload))
	if err != nil {
		return nil, err
	}
	req.Header.Set("Content-Type", "application/json")
	resp, err := hc.Do(req)
	if err != nil {
		return nil, err
	}
	defer drain(resp)
	if err := statusErr(resp.StatusCode); err != nil {
		return nil, err
	}
	var r struct {
		Sid   string `json:"sid"`
		N     string `json:"n"`
		Code  string `json:"code"`
		Known bool   `json:"known"`
	}
	if err := readJSON(resp.Body, &r); err != nil {
		return nil, fmt.Errorf("bad session answer: %w", err)
	}
	if !gs2.ValidSid(r.Sid) {
		return nil, errors.New("bad session id from the phone")
	}
	n, err := gs2.B64.DecodeString(r.N)
	if err != nil || len(n) != 32 {
		return nil, errors.New("bad session nonce from the phone")
	}
	s.sid, s.n, s.code = r.Sid, n, textsafe.Clean(r.Code, maxTextRunes)
	s.known = r.Known && gs2.ValidID(info.RID)
	return s, nil
}

// Known reports whether the phone said it remembers this receiver (§9 "known":true).
func (s *Session) Known() bool { return s.known }

// Sid is the session id (also in the QR).
func (s *Session) Sid() string { return s.sid }

// Code is the short code the phone also shows.
func (s *Session) Code() string { return s.code }

// SetKey derives the session keys and the §9 pair key from K. The caller keeps K only as long
// as the QR is shown.
func (s *Session) SetKey(k []byte) error {
	keys, err := gs2.DeriveKeys(s.sid, s.n, k)
	if err != nil {
		return err
	}
	pk, err := gs2.DerivePairKey(s.sid, s.n, k)
	if err != nil {
		return err
	}
	s.keys, s.pairKey = keys, pk
	s.haveKeys.Store(true)
	return nil
}

// PairKey returns a copy of the §9 pair key of a QR pairing (nil for a remembered session).
// It may be stored only after the phone approved the session.
func (s *Session) PairKey() []byte {
	if s.pairKey == nil {
		return nil
	}
	return append([]byte(nil), s.pairKey...)
}

// UsePairKey derives the session keys of a remembered (§9) session from PK.
func (s *Session) UsePairKey(pk []byte) error {
	keys, err := gs2.DeriveKeys(s.sid, s.n, pk)
	if err != nil {
		return err
	}
	s.keys, s.pairKey = keys, nil
	s.haveKeys.Store(true)
	return nil
}

// Claim proves possession of PK for this session (§9): POST /api/r/claim with
// proof = base64url(HMAC-SHA256(PK, "gs2 claim\n" sid "\n" base64url(N))). PK itself is never
// sent. It returns ErrAuth when the phone does not (or no longer) remember this receiver.
func (s *Session) Claim(ctx context.Context, rid string, pk []byte) error {
	if !gs2.ValidID(rid) || len(pk) != gs2.KeySize {
		return errors.New("no remembered pairing")
	}
	var buf bytes.Buffer
	enc := json.NewEncoder(&buf)
	enc.SetEscapeHTML(false)
	if err := enc.Encode(struct {
		Sid   string `json:"sid"`
		Rid   string `json:"rid"`
		Proof string `json:"proof"`
	}{s.sid, rid, gs2.ClaimProof(pk, s.sid, s.n)}); err != nil {
		return err
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, s.base+"/api/r/claim", bytes.NewReader(bytes.TrimRight(buf.Bytes(), "\n")))
	if err != nil {
		return err
	}
	req.Header.Set("Content-Type", "application/json")
	resp, err := s.hc.Do(req)
	if err != nil {
		return err
	}
	defer drain(resp)
	if err := statusErr(resp.StatusCode); err != nil {
		if errors.Is(err, ErrBusy) {
			return err
		}
		// 401, and any other refusal (a phone without §9 has no claim route): not accepted.
		return ErrAuth
	}
	var r struct {
		OK bool `json:"ok"`
	}
	if err := readJSON(resp.Body, &r); err != nil || !r.OK {
		return ErrAuth
	}
	return nil
}

func statusErr(code int) error {
	switch code {
	case http.StatusOK, http.StatusPartialContent:
		return nil
	case http.StatusUnauthorized:
		return ErrAuth
	case http.StatusForbidden:
		return ErrForbidden
	case http.StatusGone:
		return ErrExpired
	case http.StatusConflict:
		return ErrChanged
	case http.StatusNotFound:
		return ErrGone
	case http.StatusServiceUnavailable:
		return ErrBusy
	case http.StatusRequestedRangeNotSatisfiable:
		return ErrRange
	}
	return &StatusError{Code: code}
}

// State polls the pairing state: "waiting", "approved" or "expired".
func (s *Session) State(ctx context.Context) (string, error) {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, s.base+"/api/r/state?sid="+url.QueryEscape(s.sid), nil)
	if err != nil {
		return "", err
	}
	resp, err := s.hc.Do(req)
	if err != nil {
		return "", err
	}
	defer drain(resp)
	if err := statusErr(resp.StatusCode); err != nil {
		if errors.Is(err, ErrExpired) || errors.Is(err, ErrForbidden) {
			return "expired", nil
		}
		return "", err
	}
	var r struct {
		State string `json:"state"`
	}
	if err := readJSON(resp.Body, &r); err != nil {
		return "", err
	}
	switch r.State {
	case "waiting", "approved", "expired":
		return r.State, nil
	}
	return "", fmt.Errorf("unknown pairing state")
}

func sleepCtx(ctx context.Context, d time.Duration) error {
	t := time.NewTimer(d)
	defer t.Stop()
	select {
	case <-ctx.Done():
		return ctx.Err()
	case <-t.C:
		return nil
	}
}

// answer is the outcome of a signed request: either an encrypted stream (resp) or a plain
// answer whose body was checked against its X-GS-RMac signature.
type answer struct {
	resp *http.Response // encrypted stream (status 200/206 with X-GS-Enc: 1)
	body []byte         // verified body of a plain 2xx answer
}

// do sends a signed request. Every attempt uses a fresh counter.
//   - 401/403/410 are unsigned and only mean "pair again" (one 401 is retried with a new
//     counter in case it was a counter-window race).
//   - 503 waits and retries; it never leads to a decision.
//   - An encrypted stream is returned as is: its frames are authenticated one by one.
//   - Every other answer must carry a valid X-GS-RMac over (counter, status, body); if it
//     does not, the answer is ErrUnsigned, which callers treat like a network error.
func (s *Session) do(ctx context.Context, method, target, rangeHeader string, body []byte, wantStream bool) (answer, error) {
	return s.doBody(ctx, method, target, rangeHeader, body, nil, wantStream)
}

// streamBody is a request body sent as a stream (§10 upload): open is called once per
// attempt; the MAC covers the hash of an empty body.
type streamBody struct {
	open   func() (io.ReadCloser, error)
	length int64
}

func (s *Session) doBody(ctx context.Context, method, target, rangeHeader string, body []byte, sb *streamBody, wantStream bool) (answer, error) {
	if !s.haveKeys.Load() {
		return answer{}, errors.New("session not paired")
	}
	busy, denied := 0, 0
	for {
		var rd io.Reader
		var closer io.Closer
		if sb != nil {
			rc, err := sb.open()
			if err != nil {
				return answer{}, err
			}
			rd, closer = rc, rc
		} else if body != nil {
			rd = bytes.NewReader(body)
		}
		req, err := http.NewRequestWithContext(ctx, method, s.base+target, rd)
		if err != nil {
			if closer != nil {
				closer.Close()
			}
			return answer{}, err
		}
		macBody := body
		if sb != nil {
			req.ContentLength = sb.length
			macBody = nil // the empty-body hash (§10)
		}
		ctr := s.ctr.Add(1)
		canonical := gs2.Canonical(method, req.URL.RequestURI(), rangeHeader, s.sid, ctr, macBody)
		req.Header.Set("X-GS-Sid", s.sid)
		req.Header.Set("X-GS-Ctr", strconv.FormatUint(ctr, 10))
		req.Header.Set("X-GS-Mac", gs2.MAC(s.keys.Mac[:], canonical))
		if rangeHeader != "" {
			req.Header.Set("Range", rangeHeader)
		}
		if sb != nil {
			req.Header.Set("Content-Type", "application/octet-stream")
		} else if body != nil {
			req.Header.Set("Content-Type", "application/json")
		}
		resp, err := s.hc.Do(req)
		if closer != nil {
			closer.Close() // the transport closes it too; this also ends an encoder goroutine
		}
		if err != nil {
			return answer{}, err
		}
		code := resp.StatusCode
		switch code {
		case http.StatusUnauthorized:
			drain(resp)
			if denied == 0 {
				denied++
				continue
			}
			return answer{}, ErrAuth
		case http.StatusForbidden:
			// A signed 403 {"error":"not receiving"} is §10's "the phone isn't taking files";
			// an unsigned one is §4's wrong address.
			b, _ := io.ReadAll(io.LimitReader(resp.Body, maxJSON+1))
			rmac := resp.Header.Get("X-GS-RMac")
			drain(resp)
			if len(b) <= maxJSON && gs2.VerifyRespMAC(s.keys.Mac[:], ctr, code, b, rmac) {
				return answer{}, ErrNotReceiving
			}
			return answer{}, ErrForbidden
		case http.StatusGone:
			drain(resp)
			return answer{}, ErrExpired
		case http.StatusServiceUnavailable:
			drain(resp)
			if busy >= s.cfg.BusyRetries {
				return answer{}, ErrBusy
			}
			busy++
			if err := sleepCtx(ctx, s.cfg.BusyWait); err != nil {
				return answer{}, err
			}
			continue
		}
		if wantStream && (code == http.StatusOK || code == http.StatusPartialContent) && resp.Header.Get("X-GS-Enc") == "1" {
			return answer{resp: resp}, nil
		}
		b, rerr := io.ReadAll(io.LimitReader(resp.Body, maxJSON+1))
		rmac := resp.Header.Get("X-GS-RMac")
		drain(resp)
		if rerr != nil {
			return answer{}, rerr
		}
		if len(b) > maxJSON || !gs2.VerifyRespMAC(s.keys.Mac[:], ctr, code, b, rmac) {
			return answer{}, fmt.Errorf("%w (HTTP %d)", ErrUnsigned, code)
		}
		if code >= 200 && code < 300 {
			if wantStream {
				return answer{}, errors.New("the phone sent an unencrypted answer")
			}
			return answer{body: b}, nil
		}
		return answer{}, statusErr(code)
	}
}

// File is one entry of the phone's list.
type File struct {
	ID     string  `json:"id"`
	Name   string  `json:"name"`
	Size   int64   `json:"size"`
	Mime   string  `json:"mime"`
	Taken  int64   `json:"taken"`
	Month  string  `json:"month"`
	SHA256 *string `json:"sha256"`
}

// ListedSHA returns the phone's hash in lower case, or "" when it sent none.
func (f File) ListedSHA() string {
	if f.SHA256 == nil {
		return ""
	}
	return *f.SHA256
}

func validID(id string) bool {
	if id == "" || len(id) > maxIDBytes || !utf8.ValidString(id) {
		return false
	}
	for _, r := range id {
		if r < 0x20 || r == 0x7f {
			return false
		}
	}
	return true
}

func isHex64(s string) bool {
	if len(s) != 64 {
		return false
	}
	_, err := hex.DecodeString(s)
	return err == nil
}

func (s *Session) openStream(ctx context.Context, target, rangeHeader, resource string, start int64) (*Stream, error) {
	ctx, cancel := context.WithCancel(ctx)
	ans, err := s.do(ctx, http.MethodGet, target, rangeHeader, nil, true)
	if err != nil {
		cancel()
		return nil, err
	}
	resp := ans.resp
	if start > 0 && resp.StatusCode == http.StatusOK {
		start = 0 // the phone ignored the range and sends the whole file
	}
	body := newIdleReader(resp.Body, s.cfg.IdleTimeout, cancel)
	dec, err := gs2.NewDecoder(body, s.keys.Enc[:], s.sid, resource, uint64(start), s.cfg.FrameSize)
	if err != nil {
		body.Close()
		cancel()
		return nil, err
	}
	return &Stream{Start: start, dec: dec, body: body, cancel: cancel}, nil
}

// Stream is an encrypted response being read frame by frame.
type Stream struct {
	Start  int64 // absolute offset of the first byte (0 if the phone ignored the range)
	dec    *gs2.Decoder
	body   *idleReader
	cancel context.CancelFunc
}

// Next returns the next authenticated chunk and its absolute offset, or io.EOF after the
// LAST frame.
func (st *Stream) Next() ([]byte, int64, error) {
	p, off, err := st.dec.Next()
	if err != nil && err != io.EOF && st.body.stalled.Load() {
		err = fmt.Errorf("%w (%v)", ErrStalled, err)
	}
	return p, int64(off), err
}

// Close releases the connection.
func (st *Stream) Close() {
	st.cancel()
	st.body.Close()
}

// List fetches and checks the phone's file list.
func (s *Session) List(ctx context.Context) ([]File, error) {
	st, err := s.openStream(ctx, "/api/r/list", "", gs2.ListResource, 0)
	if err != nil {
		return nil, err
	}
	defer st.Close()
	var buf []byte
	for {
		p, _, err := st.Next()
		if err == io.EOF {
			break
		}
		if err != nil {
			return nil, fmt.Errorf("reading the list: %w", err)
		}
		if len(buf)+len(p) > maxListBytes {
			return nil, errors.New("the list is too large")
		}
		buf = append(buf, p...)
	}
	var doc struct {
		Files []File `json:"files"`
	}
	if err := json.Unmarshal(buf, &doc); err != nil {
		return nil, fmt.Errorf("bad list: %w", err)
	}
	if len(doc.Files) > maxFiles {
		return nil, errors.New("the list has too many files")
	}
	seen := map[string]bool{}
	out := make([]File, 0, len(doc.Files))
	for _, f := range doc.Files {
		if !validID(f.ID) || seen[f.ID] || f.Size < 0 || f.Size > maxFileSize {
			continue // unusable entry; it is reported as missing rather than trusted
		}
		seen[f.ID] = true
		if len(f.Name) > maxFileName {
			f.Name = f.Name[:maxFileName]
		}
		f.Mime = textsafe.Clean(f.Mime, 100)
		if f.SHA256 != nil {
			h := strings.ToLower(*f.SHA256)
			if isHex64(h) {
				f.SHA256 = &h
			} else {
				f.SHA256 = nil
			}
		}
		out = append(out, f)
	}
	return out, nil
}

// OpenFile starts streaming a file from absolute offset start (a Range request when start > 0).
func (s *Session) OpenFile(ctx context.Context, id string, start int64) (*Stream, error) {
	if start < 0 {
		return nil, errors.New("negative offset")
	}
	rng := ""
	if start > 0 {
		rng = "bytes=" + strconv.FormatInt(start, 10) + "-"
	}
	return s.openStream(ctx, "/api/r/file?f="+url.QueryEscape(id), rng, gs2.FileResource(id), start)
}

// Ack reports the SHA-256 and length of a file as re-read from this computer's disk. The phone
// answers whether it matches the phone's own hash of the whole current file. The answer is
// only believed when its X-GS-RMac signature is valid.
func (s *Session) Ack(ctx context.Context, id, sha string, size int64) (bool, error) {
	var buf bytes.Buffer
	enc := json.NewEncoder(&buf)
	enc.SetEscapeHTML(false)
	if err := enc.Encode(struct {
		F      string `json:"f"`
		SHA256 string `json:"sha256"`
		Size   int64  `json:"size"`
	}{id, sha, size}); err != nil {
		return false, err
	}
	ans, err := s.do(ctx, http.MethodPost, "/api/r/ack", "", bytes.TrimRight(buf.Bytes(), "\n"), false)
	if err != nil {
		return false, err
	}
	var r struct {
		OK       *bool `json:"ok"`
		Verified *bool `json:"verified"`
	}
	if err := json.Unmarshal(ans.body, &r); err != nil || r.OK == nil || r.Verified == nil {
		return false, fmt.Errorf("%w: unreadable ack answer", ErrUnsigned)
	}
	return *r.OK && *r.Verified, nil
}

// Done ends the session on the phone.
func (s *Session) Done(ctx context.Context) error {
	_, err := s.do(ctx, http.MethodPost, "/api/r/done", "", []byte{}, false)
	return err
}
