package client

// "Bring back to phone" (gs2 §10): the receiver uploads files to the phone inside an approved
// session. The phone stays the server.
//
// Security-sensitive: needs a person's review before release.

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strings"
	"sync/atomic"
	"time"

	"galleryslim/receiver/internal/gs2"
	"galleryslim/receiver/internal/textsafe"
)

const (
	// MaxUpload is the largest file the phone takes (§10: ≤ 4 GiB per file).
	MaxUpload = 4 << 30
	// MaxOfferFiles is the most files in one offer.
	MaxOfferFiles = 500
	// maxOfferBody is the largest offer body.
	maxOfferBody = 16 << 10
	// UploadStall aborts an upload the phone stops reading for this long (§10).
	UploadStall = 30 * time.Second
	maxWants    = 200000
	maxWantsLen = 64 << 20
)

// Want is a file the owner asked to get back (from "Moved to computer").
type Want struct {
	ID     string `json:"id"`
	Name   string `json:"name"`
	Size   int64  `json:"size"`
	SHA256 string `json:"sha256"`
	Month  string `json:"month"`
	Taken  int64  `json:"taken"`
}

// validUpID is the §6 file id shape: [A-Za-z0-9_-]{1,64}.
func validUpID(id string) bool {
	if id == "" || len(id) > 64 {
		return false
	}
	for i := 0; i < len(id); i++ {
		c := id[i]
		if !(c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9' || c == '_' || c == '-') {
			return false
		}
	}
	return true
}

// Wants fetches the wanted list (encrypted, resource "wants"). An empty list means free
// choice on the computer. ErrNotReceiving: the phone is not in a receiving mode.
func (s *Session) Wants(ctx context.Context) ([]Want, error) {
	st, err := s.openStream(ctx, "/api/r/wants", "", gs2.WantsResource, 0)
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
			return nil, fmt.Errorf("reading the wanted list: %w", err)
		}
		if len(buf)+len(p) > maxWantsLen {
			return nil, errors.New("the wanted list is too large")
		}
		buf = append(buf, p...)
	}
	var doc struct {
		Files []Want `json:"files"`
	}
	if err := json.Unmarshal(buf, &doc); err != nil {
		return nil, fmt.Errorf("bad wanted list: %w", err)
	}
	if len(doc.Files) > maxWants {
		return nil, errors.New("the wanted list has too many files")
	}
	seen := map[string]bool{}
	out := make([]Want, 0, len(doc.Files))
	for _, w := range doc.Files {
		w.SHA256 = strings.ToLower(w.SHA256)
		if !validUpID(w.ID) || seen[w.ID] || w.Size < 0 || w.Size > MaxUpload || !isHex64(w.SHA256) {
			continue
		}
		seen[w.ID] = true
		if len(w.Name) > maxFileName {
			w.Name = w.Name[:maxFileName]
		}
		w.Month = textsafe.Clean(w.Month, 16)
		out = append(out, w)
	}
	return out, nil
}

// OfferFile describes a file the person chose to send (free choice).
type OfferFile struct {
	Name   string `json:"name"`
	Size   int64  `json:"size"`
	SHA256 string `json:"sha256"`
	Mime   string `json:"mime"`
	Taken  int64  `json:"taken"`
}

// OfferResult is the phone's decision for one offered file: an upload id, or why not.
type OfferResult struct {
	ID  string // "" when refused
	Why string // cleaned reason when refused
}

// Offer proposes files (free choice). It splits long lists into offers of at most 500 files
// and 16 KiB. The answer must be signed. Results are in the order of files.
//
// The phone answers {"accepted":[ids…],"refused":[{name,why}…]}: refused entries are matched
// to offered files by name (in order), and accepted ids are given to the remaining files in
// order; an answer whose counts don't add up is rejected as unreadable.
func (s *Session) Offer(ctx context.Context, files []OfferFile) ([]OfferResult, error) {
	out := make([]OfferResult, 0, len(files))
	for start := 0; start < len(files); {
		end := min(len(files), start+MaxOfferFiles)
		var body []byte
		for {
			b, err := offerBody(files[start:end])
			if err != nil {
				return nil, err
			}
			if len(b) <= maxOfferBody {
				body = b
				break
			}
			if end-start == 1 {
				return nil, errors.New("a file name is too long to offer")
			}
			end = start + (end-start)/2
		}
		res, err := s.offerOnce(ctx, files[start:end], body)
		if err != nil {
			return nil, err
		}
		out = append(out, res...)
		start = end
	}
	return out, nil
}

func offerBody(files []OfferFile) ([]byte, error) {
	var buf bytes.Buffer
	enc := json.NewEncoder(&buf)
	enc.SetEscapeHTML(false)
	if err := enc.Encode(struct {
		Files []OfferFile `json:"files"`
	}{files}); err != nil {
		return nil, err
	}
	return bytes.TrimRight(buf.Bytes(), "\n"), nil
}

func (s *Session) offerOnce(ctx context.Context, files []OfferFile, body []byte) ([]OfferResult, error) {
	ans, err := s.do(ctx, http.MethodPost, "/api/r/offer", "", body, false)
	if err != nil {
		return nil, err
	}
	var r struct {
		OK       bool     `json:"ok"`
		Accepted []string `json:"accepted"`
		Refused  []struct {
			Name string `json:"name"`
			Why  string `json:"why"`
		} `json:"refused"`
	}
	if err := json.Unmarshal(ans.body, &r); err != nil || !r.OK || len(r.Accepted)+len(r.Refused) != len(files) {
		return nil, fmt.Errorf("%w: unreadable offer answer", ErrUnsigned)
	}
	res := make([]OfferResult, len(files))
	refused := make([]bool, len(files))
	for _, x := range r.Refused {
		matched := false
		for i, f := range files {
			if !refused[i] && f.Name == x.Name {
				refused[i], matched = true, true
				why := textsafe.Clean(x.Why, 200)
				if why == "" {
					why = "The phone did not take this file"
				}
				res[i].Why = why
				break
			}
		}
		if !matched {
			return nil, fmt.Errorf("%w: offer answer names an unknown file", ErrUnsigned)
		}
	}
	next := 0
	seen := map[string]bool{}
	for i := range files {
		if refused[i] {
			continue
		}
		id := r.Accepted[next]
		next++
		if !validUpID(id) || seen[id] {
			return nil, fmt.Errorf("%w: bad upload id in offer answer", ErrUnsigned)
		}
		seen[id] = true
		res[i].ID = id
	}
	return res, nil
}

// UploadResult is the phone's answer to an upload.
type UploadResult struct {
	Saved bool
	URI   string // content URI on the phone (cleaned; for the log only)
	Why   string // cleaned reason when not saved
}

// Upload sends one file as an encrypted frame stream (resource "u:<id>", offset 0, LAST
// frame). open is called for every attempt and must return the file's bytes from the start;
// size must be the exact length. progress, if set, is called with the plaintext bytes read.
// The stream is cut when the phone reads nothing for UploadStall. The answer must be signed.
func (s *Session) Upload(ctx context.Context, id string, size int64, open func() (io.ReadCloser, error), progress func(int64)) (UploadResult, error) {
	if !validUpID(id) {
		return UploadResult{}, errors.New("bad upload id")
	}
	if size < 0 || size > MaxUpload {
		return UploadResult{}, errors.New("the file is too large for the phone (over 4 GB)")
	}
	ctx, cancel := context.WithCancel(ctx)
	defer cancel()
	var stalled atomic.Bool
	watchdog := time.AfterFunc(UploadStall, func() { stalled.Store(true); cancel() })
	defer watchdog.Stop()
	sb := &streamBody{
		length: gs2.UploadLength(size, s.cfg.FrameSize),
		open: func() (io.ReadCloser, error) {
			src, err := open()
			if err != nil {
				return nil, err
			}
			pr, pw := io.Pipe()
			go func() {
				defer src.Close()
				pw.CloseWithError(s.encodeUpload(pw, id, size, src, func(n int64) {
					watchdog.Reset(UploadStall)
					if progress != nil {
						progress(n)
					}
				}))
			}()
			return pr, nil
		},
	}
	ans, err := s.doBody(ctx, http.MethodPost, "/api/r/upload?f="+url.QueryEscape(id), "", nil, sb, false)
	if err != nil {
		if stalled.Load() {
			return UploadResult{}, fmt.Errorf("%w (%v)", ErrStalled, err)
		}
		return UploadResult{}, err
	}
	var r struct {
		OK    *bool  `json:"ok"`
		Saved bool   `json:"saved"`
		URI   string `json:"uri"`
		Why   string `json:"why"`
	}
	if err := json.Unmarshal(ans.body, &r); err != nil || r.OK == nil {
		return UploadResult{}, fmt.Errorf("%w: unreadable upload answer", ErrUnsigned)
	}
	res := UploadResult{Saved: *r.OK && r.Saved, URI: textsafe.Clean(r.URI, 200), Why: textsafe.Clean(r.Why, 200)}
	if !res.Saved && res.Why == "" {
		res.Why = "The phone did not save the file"
	}
	return res, nil
}

// encodeUpload writes the frame stream of exactly size bytes from src.
func (s *Session) encodeUpload(w io.Writer, id string, size int64, src io.Reader, progress func(int64)) error {
	enc, err := gs2.NewStreamEncoder(w, s.keys.Enc[:], s.sid, gs2.UploadResource(id), s.cfg.FrameSize)
	if err != nil {
		return err
	}
	buf := make([]byte, 256<<10)
	var sent int64
	for sent < size {
		n, err := src.Read(buf[:min(int64(len(buf)), size-sent)])
		if n > 0 {
			if _, werr := enc.Write(buf[:n]); werr != nil {
				return werr
			}
			sent += int64(n)
			progress(int64(n))
		}
		if err == io.EOF && sent < size {
			return errors.New("the file got shorter while it was being sent")
		}
		if err != nil && err != io.EOF {
			return err
		}
	}
	return enc.Close()
}
