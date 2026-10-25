package receive

// "Bring back to phone" (gs2 §10): upload files from this computer to the phone, either the
// files the phone asks for (its wanted list) or files the person picks on the computer.
//
// Security-sensitive: needs a person's review before release.

import (
	"context"
	"errors"
	"fmt"
	"io"
	"mime"
	"os"
	"path/filepath"
	"strings"
	"time"

	"galleryslim/receiver/internal/client"
	"galleryslim/receiver/internal/store"
)

// BackOutcome is what happened to one file sent to the phone.
type BackOutcome string

const (
	// Sent: the phone checked size and SHA-256 and saved the file.
	Sent BackOutcome = "sent"
	// NotFound: the receiver has no file with these bytes ("Not found on this computer").
	NotFound BackOutcome = "not-found"
	// Refused: the phone did not take the offered file (type, space).
	Refused BackOutcome = "refused"
	// BackFailed: the upload failed or the phone did not save it.
	BackFailed BackOutcome = "failed"
)

// NotFoundText is the detail of a NotFound result.
const NotFoundText = "Not found on this computer"

// BackResult is the outcome of one file.
type BackResult struct {
	Key     string // wanted id, or "pick-<n>" for a picked file
	Name    string
	Path    string // full path on this computer ("" when not found)
	Size    int64
	Outcome BackOutcome
	Detail  string
}

// BackEvents receives progress of a send to the phone. Methods are called from one goroutine.
type BackEvents interface {
	BackListed(keys, names []string, sizes []int64)
	BackStarted(key string)
	BackProgress(delta int64)
	BackDone(r BackResult)
}

// BackSummary counts the outcomes.
type BackSummary struct{ Sent, NotFound, Refused, Failed int }

func (s *BackSummary) add(o BackOutcome) {
	switch o {
	case Sent:
		s.Sent++
	case NotFound:
		s.NotFound++
	case Refused:
		s.Refused++
	default:
		s.Failed++
	}
}

// stopErr reports errors after which no further file can be sent in this session.
func stopErr(err error) bool {
	return errors.Is(err, client.ErrNotReceiving) || errors.Is(err, client.ErrAuth) ||
		errors.Is(err, client.ErrExpired) || errors.Is(err, client.ErrForbidden) ||
		errors.Is(err, context.Canceled) || errors.Is(err, context.DeadlineExceeded)
}

// upload sends one file, retrying once after a network error. The file's size and SHA-256
// were checked just before by the caller.
func upload(ctx context.Context, sess *client.Session, id, path string, size int64, ev BackEvents) (client.UploadResult, error) {
	var res client.UploadResult
	var err error
	var sent int64 // progress shown for the current attempt
	for attempt := 0; attempt < 2; attempt++ {
		res, err = sess.Upload(ctx, id, size, func() (io.ReadCloser, error) {
			if sent > 0 { // a new attempt: take back the progress shown
				ev.BackProgress(-sent)
				sent = 0
			}
			return os.Open(path)
		}, func(n int64) {
			sent += n
			ev.BackProgress(n)
		})
		if err == nil || stopErr(err) || ctx.Err() != nil {
			break
		}
		// The phone refuses before reading the body, so a refusal can arrive as a connection
		// reset. Ask whether it still takes files before trying again.
		wctx, cancel := context.WithTimeout(ctx, 15*time.Second)
		_, werr := sess.Wants(wctx)
		cancel()
		if werr != nil && stopErr(werr) {
			err = werr
			break
		}
	}
	if (err != nil || !res.Saved) && sent > 0 {
		ev.BackProgress(-sent) // the bar shows only what arrived
	}
	return res, err
}

func backResult(r BackResult, res client.UploadResult, err error) BackResult {
	switch {
	case err != nil:
		r.Outcome, r.Detail = BackFailed, Friendly(err)
	case res.Saved:
		r.Outcome = Sent
	default:
		r.Outcome, r.Detail = BackFailed, res.Why
	}
	return r
}

// SendWanted uploads the files of the phone's wanted list, one at a time. Files this computer
// can't find with the same bytes are reported NotFound and skipped. It stops at the first
// error that ends the session (for example ErrNotReceiving) and returns it.
func SendWanted(ctx context.Context, sess *client.Session, folder *store.Folder, wants []client.Want, ev BackEvents) (BackSummary, error) {
	var sum BackSummary
	keys, names, sizes := make([]string, len(wants)), make([]string, len(wants)), make([]int64, len(wants))
	for i, w := range wants {
		keys[i], names[i], sizes[i] = w.ID, store.CleanName(w.Name, ""), w.Size
	}
	ev.BackListed(keys, names, sizes)
	for i, w := range wants {
		if ctx.Err() != nil {
			return sum, ctx.Err()
		}
		r := BackResult{Key: w.ID, Name: names[i], Size: w.Size}
		ev.BackStarted(w.ID)
		path, ok := folder.Locate(w.SHA256, w.Size, w.Month, w.Name)
		if !ok {
			r.Outcome, r.Detail = NotFound, NotFoundText
			sum.add(r.Outcome)
			ev.BackDone(r)
			continue
		}
		r.Path = path
		res, err := upload(ctx, sess, w.ID, path, w.Size, ev)
		r = backResult(r, res, err)
		sum.add(r.Outcome)
		ev.BackDone(r)
		if err != nil && stopErr(err) {
			return sum, err
		}
	}
	return sum, nil
}

// uploadMimes are the types the phone takes (§10: images and videos); others are offered
// as application/octet-stream and refused by the phone.
var uploadMimes = map[string]string{
	".jpg": "image/jpeg", ".jpeg": "image/jpeg", ".png": "image/png", ".gif": "image/gif",
	".webp": "image/webp", ".heic": "image/heic", ".heif": "image/heif", ".avif": "image/avif",
	".dng": "image/x-adobe-dng", ".bmp": "image/bmp", ".tif": "image/tiff", ".tiff": "image/tiff",
	".mp4": "video/mp4", ".m4v": "video/x-m4v", ".mov": "video/quicktime", ".3gp": "video/3gpp",
	".mkv": "video/x-matroska", ".webm": "video/webm", ".avi": "video/x-msvideo",
}

// MimeOf guesses a file's media type from its extension.
func MimeOf(name string) string {
	ext := strings.ToLower(filepath.Ext(name))
	if m, ok := uploadMimes[ext]; ok {
		return m
	}
	if m := mime.TypeByExtension(ext); m != "" {
		if base, _, err := mime.ParseMediaType(m); err == nil {
			return base
		}
	}
	return "application/octet-stream"
}

type picked struct {
	path  string
	name  string
	size  int64
	mtime time.Time
	sha   string
	err   error
}

// SendChosen offers files the person picked and uploads those the phone accepts, one at a
// time. Every file is hashed on disk before the offer and checked again (size and time)
// before its upload.
func SendChosen(ctx context.Context, sess *client.Session, paths []string, ev BackEvents) (BackSummary, error) {
	var sum BackSummary
	files := make([]picked, len(paths))
	keys, names, sizes := make([]string, len(paths)), make([]string, len(paths)), make([]int64, len(paths))
	for i, p := range paths {
		f := picked{path: p, name: filepath.Base(p)}
		if !filepath.IsAbs(p) {
			f.err = errors.New("not a full path")
		} else if fi, err := os.Lstat(p); err != nil {
			f.err = err
		} else if !fi.Mode().IsRegular() {
			f.err = errors.New("not a file")
		} else if fi.Size() > client.MaxUpload {
			f.err = errors.New("larger than 4 GB")
		} else {
			f.size, f.mtime = fi.Size(), fi.ModTime()
		}
		files[i] = f
		keys[i], names[i], sizes[i] = fmt.Sprintf("pick-%d", i), store.CleanName(f.name, ""), f.size
	}
	ev.BackListed(keys, names, sizes)
	var offer []client.OfferFile
	var offered []int
	for i := range files {
		f := &files[i]
		if f.err == nil {
			sha, n, err := store.HashFile(f.path)
			if err != nil || n != f.size {
				f.err = errors.New("could not be read")
			} else {
				f.sha = sha
			}
		}
		if f.err != nil {
			r := BackResult{Key: keys[i], Name: names[i], Path: f.path, Size: f.size, Outcome: NotFound, Detail: "Can't send this file: " + f.err.Error()}
			sum.add(r.Outcome)
			ev.BackDone(r)
			continue
		}
		offer = append(offer, client.OfferFile{Name: names[i], Size: f.size, SHA256: f.sha, Mime: MimeOf(f.name), Taken: f.mtime.UnixMilli()})
		offered = append(offered, i)
	}
	if len(offer) == 0 {
		return sum, nil
	}
	decisions, err := sess.Offer(ctx, offer)
	if err != nil {
		for _, i := range offered {
			r := BackResult{Key: keys[i], Name: names[i], Path: files[i].path, Size: files[i].size, Outcome: BackFailed, Detail: Friendly(err)}
			sum.add(r.Outcome)
			ev.BackDone(r)
		}
		return sum, err
	}
	for j, i := range offered {
		f := files[i]
		r := BackResult{Key: keys[i], Name: names[i], Path: f.path, Size: f.size}
		if decisions[j].ID == "" {
			r.Outcome, r.Detail = Refused, decisions[j].Why
			sum.add(r.Outcome)
			ev.BackDone(r)
			continue
		}
		if ctx.Err() != nil {
			return sum, ctx.Err()
		}
		ev.BackStarted(r.Key)
		if fi, err := os.Lstat(f.path); err != nil || !fi.Mode().IsRegular() || fi.Size() != f.size || !fi.ModTime().Equal(f.mtime) {
			r.Outcome, r.Detail = BackFailed, "The file changed on this computer; choose it again"
			sum.add(r.Outcome)
			ev.BackDone(r)
			continue
		}
		res, err := upload(ctx, sess, decisions[j].ID, f.path, f.size, ev)
		r = backResult(r, res, err)
		sum.add(r.Outcome)
		ev.BackDone(r)
		if err != nil && stopErr(err) {
			return sum, err
		}
	}
	return sum, nil
}
