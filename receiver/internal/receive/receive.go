package receive

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"fmt"
	"io"
	"os"
	"strings"
	"sync"
	"time"

	"galleryslim/receiver/internal/client"
	"galleryslim/receiver/internal/store"
)

// Outcome of one file.
type Outcome string

const (
	Verified Outcome = "verified" // on disk, and the phone confirmed the bytes match
	Saved    Outcome = "saved"    // on disk, but the phone could not confirm it
	Skipped  Outcome = "skipped"  // no longer on the phone
	Failed   Outcome = "failed"
)

// Result describes what happened to one file.
type Result struct {
	File    client.File
	Name    string // clean file name
	Rel     string // "<month>/<name>" inside the folder, when saved
	Outcome Outcome
	Already bool // it was already on this computer and only verified
	Detail  string
}

// Events receives progress. Methods are called from several goroutines.
type Events interface {
	Listed(files []client.File, totalBytes int64)
	FileStarted(f client.File, name string)
	Progress(delta int64)
	FileDone(r Result)
}

// NopEvents ignores everything.
type NopEvents struct{}

func (NopEvents) Listed([]client.File, int64)     {}
func (NopEvents) FileStarted(client.File, string) {}
func (NopEvents) Progress(int64)                  {}
func (NopEvents) FileDone(Result)                 {}

// ErrNoSpace means the folder's drive is too full for the files.
type ErrNoSpace struct{ Need, Free uint64 }

func (e *ErrNoSpace) Error() string {
	return fmt.Sprintf("not enough free space: need %s, free %s", HumanBytes(int64(e.Need)), HumanBytes(int64(e.Free)))
}

// Options configures Run.
type Options struct {
	Session     *client.Session
	Folder      *store.Folder
	Phone       string // phone name, part of the resume key
	Parallel    int    // concurrent downloads, at most 3
	Events      Events
	MaxAttempts int           // per file, default 5
	RetryWait   time.Duration // first retry delay, default 1 s (doubles, max 15 s)
	KeepAwake   func() (release func())
	// Skip reports files the phone already confirmed earlier in this run (used by "Try
	// again" on the same session); they are reported as verified without being touched.
	Skip        func(f client.File) bool
	SpaceMargin uint64 // extra free space required, default 50 MB
}

// Summary counts outcomes.
type Summary struct {
	Verified, Saved, Skipped, Failed, Already int
	Bytes                                     int64
}

// isFatal reports errors that end the whole session.
func isFatal(err error) bool {
	return errors.Is(err, client.ErrAuth) || errors.Is(err, client.ErrForbidden) || errors.Is(err, client.ErrExpired)
}

// testAfterSave, when set by tests, runs after a file is moved into place and before it is
// re-read for the ack.
var testAfterSave func(path string)

type runner struct {
	o      Options
	cancel context.CancelCauseFunc

	listMu   sync.Mutex
	listAt   time.Time
	listByID map[string]client.File
}

// Run lists the phone's files and receives them. It returns a session-level error (expired,
// authentication, refused) or nil when every file was handled; per-file failures are in the
// results and the summary.
func Run(ctx context.Context, o Options) (Summary, error) {
	var sum Summary
	if o.Events == nil {
		o.Events = NopEvents{}
	}
	if o.Parallel <= 0 || o.Parallel > 3 {
		o.Parallel = 3
	}
	if o.MaxAttempts <= 0 {
		o.MaxAttempts = 5
	}
	if o.RetryWait <= 0 {
		o.RetryWait = time.Second
	}
	if o.SpaceMargin == 0 {
		o.SpaceMargin = 50 << 20
	}
	var files []client.File
	var err error
	for i := 0; ; i++ {
		files, err = o.Session.List(ctx)
		if err == nil || isFatal(err) || ctx.Err() != nil || i == 2 {
			break
		}
		if serr := sleep(ctx, o.RetryWait<<i); serr != nil {
			return sum, serr
		}
	}
	if err != nil {
		return sum, err
	}
	r := &runner{o: o}
	r.setList(files)
	var total int64
	for _, f := range files {
		total += f.Size
	}
	o.Events.Listed(files, total)

	if err := r.checkSpace(files); err != nil {
		return sum, err
	}
	if o.KeepAwake != nil {
		release := o.KeepAwake()
		defer release()
	}

	ctx, cancel := context.WithCancelCause(ctx)
	defer cancel(nil)
	r.cancel = cancel
	jobs := make(chan client.File)
	results := make(chan Result)
	var wg sync.WaitGroup
	for i := 0; i < o.Parallel; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			for f := range jobs {
				res := r.process(ctx, f)
				results <- res
			}
		}()
	}
	go func() {
		defer close(jobs)
		for _, f := range files {
			select {
			case jobs <- f:
			case <-ctx.Done():
				return
			}
		}
	}()
	go func() { wg.Wait(); close(results) }()
	for res := range results {
		switch res.Outcome {
		case Verified:
			sum.Verified++
			if res.Already {
				sum.Already++
			}
		case Saved:
			sum.Saved++
		case Skipped:
			sum.Skipped++
		default:
			sum.Failed++
		}
		if res.Outcome == Verified || res.Outcome == Saved {
			sum.Bytes += res.File.Size
		}
		o.Events.FileDone(res)
	}
	_ = o.Folder.Flush()
	if cause := context.Cause(ctx); cause != nil && !errors.Is(cause, context.Canceled) {
		return sum, cause
	}
	if err := ctx.Err(); err != nil {
		return sum, err
	}
	sum.Failed += len(files) - (sum.Verified + sum.Saved + sum.Skipped + sum.Failed)
	if sum.Failed == 0 {
		dctx, dcancel := context.WithTimeout(context.Background(), 10*time.Second)
		_ = o.Session.Done(dctx)
		dcancel()
	}
	return sum, nil
}

func (r *runner) setList(files []client.File) {
	m := make(map[string]client.File, len(files))
	for _, f := range files {
		m[f.ID] = f
	}
	r.listMu.Lock()
	r.listByID, r.listAt = m, time.Now()
	r.listMu.Unlock()
}

// relist returns the current list entry for id, fetching the list again unless another
// worker already fetched it after `since` (when this worker saw the change).
func (r *runner) relist(ctx context.Context, id string, since time.Time) (client.File, bool, error) {
	r.listMu.Lock()
	defer r.listMu.Unlock()
	if !r.listAt.After(since) {
		files, err := r.o.Session.List(ctx)
		if err != nil {
			return client.File{}, false, err
		}
		m := make(map[string]client.File, len(files))
		for _, f := range files {
			m[f.ID] = f
		}
		r.listByID, r.listAt = m, time.Now()
	}
	f, ok := r.listByID[id]
	return f, ok, nil
}

func (r *runner) partPath(f client.File) (string, error) {
	return r.o.Folder.PartPath(store.PartName(r.o.Phone, f.ID, f.Size, f.Taken, f.ListedSHA()))
}

func (r *runner) checkSpace(files []client.File) error {
	free, err := r.o.Folder.FreeBytes()
	if err != nil {
		return nil // unknown on this system; the write itself will fail cleanly if full
	}
	var need uint64
	for _, f := range files {
		if r.o.Folder.LikelyHave(r.o.Phone, f.ID, f.Size) {
			continue
		}
		n := uint64(f.Size)
		if part, err := r.partPath(f); err == nil {
			if fi, err := os.Stat(part); err == nil && fi.Size() <= f.Size {
				n -= uint64(fi.Size())
			}
		}
		need += n
	}
	if need+r.o.SpaceMargin > free {
		return &ErrNoSpace{Need: need, Free: free}
	}
	return nil
}

func takenTime(ms int64) time.Time {
	if ms <= 0 {
		return time.Time{}
	}
	t := time.UnixMilli(ms)
	if t.After(time.Now().Add(24 * time.Hour)) {
		return time.Time{}
	}
	return t
}

// errSizeMismatch means the phone's stream did not match the listed size (the file changed).
var errSizeMismatch = errors.New("the file's size on the phone changed")

// fetch downloads f into its part file, resuming from what is already there, and returns
// the SHA-256 of the complete part. onPos is called with the absolute offset reached.
func (r *runner) fetch(ctx context.Context, f client.File, restart bool, onPos func(int64)) (string, string, error) {
	part, err := r.partPath(f)
	if err != nil {
		return "", "", err
	}
	fh, err := os.OpenFile(part, os.O_RDWR|os.O_CREATE, 0o644)
	if err != nil {
		return "", "", err
	}
	defer fh.Close()
	fi, err := fh.Stat()
	if err != nil {
		return "", "", err
	}
	have := fi.Size()
	if have > f.Size || restart {
		if err := fh.Truncate(0); err != nil {
			return "", "", err
		}
		have = 0
	}
	// Resume where the part file ends. A complete part is re-confirmed by fetching its last
	// byte again, so a stream with the LAST frame is always seen before the file is used.
	start := have
	if f.Size > 0 && have == f.Size {
		start = f.Size - 1
	}
	h := sha256.New()
	if start > 0 {
		if _, err := io.Copy(h, io.NewSectionReader(fh, 0, start)); err != nil {
			return "", "", err
		}
		onPos(start)
	}
	st, err := r.o.Session.OpenFile(ctx, f.ID, start)
	if err != nil {
		return "", "", err
	}
	defer st.Close()
	if st.Start != start {
		// The phone ignored the range and sends everything.
		start = 0
		h.Reset()
		if err := fh.Truncate(0); err != nil {
			return "", "", err
		}
	}
	pos := start
	for {
		p, off, err := st.Next()
		if err == io.EOF {
			break
		}
		if err != nil {
			return "", "", err
		}
		if off != pos {
			return "", "", fmt.Errorf("frame at offset %d, expected %d", off, pos)
		}
		if pos+int64(len(p)) > f.Size {
			return "", "", errSizeMismatch
		}
		if _, err := fh.WriteAt(p, pos); err != nil {
			return "", "", err
		}
		h.Write(p)
		pos += int64(len(p))
		onPos(pos)
	}
	if pos != f.Size {
		return "", "", errSizeMismatch
	}
	if err := fh.Truncate(f.Size); err != nil {
		return "", "", err
	}
	if err := fh.Sync(); err != nil {
		return "", "", err
	}
	if err := fh.Close(); err != nil {
		return "", "", err
	}
	return part, hex.EncodeToString(h.Sum(nil)), nil
}

func (r *runner) fail(f client.File, name string, err error) Result {
	if isFatal(err) {
		r.cancel(err)
	}
	return Result{File: f, Name: name, Outcome: Failed, Detail: friendly(err)}
}

func (r *runner) ack(ctx context.Context, id, sum string, size int64) (bool, error) {
	var err error
	for i := 0; i < 3; i++ {
		var ok bool
		ok, err = r.o.Session.Ack(ctx, id, sum, size)
		if err == nil || isFatal(err) || ctx.Err() != nil {
			return ok, err
		}
		if sleepErr := sleep(ctx, r.o.RetryWait); sleepErr != nil {
			return false, sleepErr
		}
	}
	return false, err
}

func sleep(ctx context.Context, d time.Duration) error {
	t := time.NewTimer(d)
	defer t.Stop()
	select {
	case <-ctx.Done():
		return ctx.Err()
	case <-t.C:
		return nil
	}
}

// process handles one file from start to finish.
func (r *runner) process(ctx context.Context, f client.File) Result {
	name := store.CleanName(f.Name, f.Mime)
	r.o.Events.FileStarted(f, name)
	if r.o.Skip != nil && r.o.Skip(f) {
		r.o.Events.Progress(f.Size)
		return Result{File: f, Name: name, Outcome: Verified, Already: true, Detail: "Received and checked earlier"}
	}
	var reported int64
	onPos := func(pos int64) {
		if pos > reported {
			r.o.Events.Progress(pos - reported)
			reported = pos
		}
	}

	kept := "" // an earlier copy the phone did not confirm (kept on disk)

	// Already here? The manifest is only a hint: FindExisting re-hashes the file on disk.
	if ex, ok := r.o.Folder.FindExisting(r.o.Phone, f.ID, f.Size, f.ListedSHA(), f.Month, name); ok {
		verified, err := r.ack(ctx, f.ID, ex.SHA256, f.Size)
		if err != nil {
			res := r.fail(f, name, err)
			if !isFatal(err) && ctx.Err() == nil {
				res.Rel, res.Outcome, res.Already = ex.Rel, Saved, true
				res.Detail = "Already on this computer, but the phone couldn't be asked to confirm it"
			}
			return res
		}
		if verified {
			onPos(f.Size)
			return Result{File: f, Name: name, Rel: ex.Rel, Outcome: Verified, Already: true,
				Detail: "Already on this computer (checked)"}
		}
		// The copy here differs from the phone's file. It stays where it is; a new copy is
		// received under a new name.
		r.o.Folder.MarkUnconfirmed(ex.Rel)
		kept = ex.Rel
	}

	fromScratch := false // a full re-download has been tried
	restart := false     // the next fetch must ignore any part file
	relists := 0
	for attempt := 1; ; {
		if ctx.Err() != nil {
			return r.fail(f, name, context.Cause(ctx))
		}
		part, streamSum, err := r.fetch(ctx, f, restart, onPos)
		failedAt := time.Now()
		restart = false
		if err != nil {
			switch {
			case isFatal(err):
				return r.fail(f, name, err)
			case ctx.Err() != nil:
				return r.fail(f, name, context.Cause(ctx))
			case errors.Is(err, client.ErrGone):
				return Result{File: f, Name: name, Outcome: Skipped, Detail: "No longer on the phone"}
			case errors.Is(err, client.ErrRange):
				restart = true // the phone refused the resume point: start the file again
			case errors.Is(err, client.ErrChanged) || errors.Is(err, errSizeMismatch):
				// The phone's file changed: what we have may mix old and new bytes.
				if part, perr := r.partPath(f); perr == nil {
					os.Remove(part)
				}
				if relists++; relists > 2 {
					return r.fail(f, name, err)
				}
				nf, ok, lerr := r.relist(ctx, f.ID, failedAt)
				if lerr != nil {
					return r.fail(f, name, lerr)
				}
				if !ok {
					return Result{File: f, Name: name, Outcome: Skipped, Detail: "No longer on the phone"}
				}
				f = nf
				name = store.CleanName(f.Name, f.Mime)
				continue
			}
			if attempt++; attempt > r.o.MaxAttempts {
				return r.fail(f, name, err)
			}
			wait := min(r.o.RetryWait<<(attempt-2), 15*time.Second)
			if err := sleep(ctx, wait); err != nil {
				return r.fail(f, name, context.Cause(ctx))
			}
			continue
		}
		if listed := f.ListedSHA(); listed != "" && listed != streamSum {
			os.Remove(part)
			if fromScratch {
				return Result{File: f, Name: name, Outcome: Failed, Detail: "The received copy doesn't match the phone's checksum"}
			}
			fromScratch, restart = true, true
			continue
		}

		rel, err := r.o.Folder.Finalize(part, f.Month, name, takenTime(f.Taken))
		if err != nil {
			return r.fail(f, name, fmt.Errorf("saving: %w", err))
		}
		abs := r.o.Folder.Abs(rel)
		if testAfterSave != nil {
			testAfterSave(abs)
		}
		// Read the file back from disk; only these bytes are acknowledged.
		diskSum, n, err := store.HashFile(abs)
		if err != nil {
			return r.fail(f, name, fmt.Errorf("reading back: %w", err))
		}
		if n != f.Size {
			return Result{File: f, Name: name, Rel: rel, Outcome: Failed, Detail: "The saved file has the wrong size on disk"}
		}
		r.o.Folder.Record(rel, store.ManifestEntry{Size: n, SHA256: diskSum, FileID: f.ID, Phone: r.o.Phone})
		verified, err := r.ack(ctx, f.ID, diskSum, n)
		if err != nil {
			res := r.fail(f, name, err)
			res.Rel, res.Outcome = rel, Saved
			res.Detail = "Saved, but the phone could not be asked to confirm it"
			return res
		}
		if verified {
			detail := longPathNote(abs)
			if kept != "" {
				detail = strings.TrimSpace("An earlier copy (" + kept + ") couldn't be confirmed and was kept. " + detail)
			}
			return Result{File: f, Name: name, Rel: rel, Outcome: Verified, Detail: detail}
		}
		// The phone says this copy differs from its file. A finished file is never deleted or
		// replaced because of an answer from the phone: it is kept, flagged in the manifest,
		// and one fresh download is saved under a new name (a resumed part may have held bytes
		// of an older version of the file).
		r.o.Folder.MarkUnconfirmed(rel)
		if !fromScratch && kept == "" {
			kept = rel
			fromScratch, restart = true, true
			continue
		}
		return Result{File: f, Name: name, Rel: rel, Outcome: Saved,
			Detail: "Couldn't confirm — kept. The phone says this copy doesn't match, so it won't be offered for freeing space."}
	}
}

func longPathNote(abs string) string {
	if len([]rune(abs)) >= 260 && strings.Contains(abs, `\`) {
		return "The full path is longer than 260 characters; some Windows programs can't open it"
	}
	return ""
}

func friendly(err error) string {
	switch {
	case err == nil:
		return ""
	case errors.Is(err, client.ErrExpired):
		return "The phone ended the session"
	case errors.Is(err, client.ErrAuth):
		return "The phone did not accept this computer's requests"
	case errors.Is(err, client.ErrForbidden):
		return "The phone refused this computer's address"
	case errors.Is(err, client.ErrBusy):
		return "The phone stayed busy"
	case errors.Is(err, client.ErrStalled):
		return "The connection stopped; try again"
	case errors.Is(err, client.ErrNotReceiving):
		return "The phone is not taking files right now. On the phone, open \"Bring back\" or \"Receive from computer\"."
	case errors.Is(err, client.ErrUnsigned):
		return "The phone's answer could not be checked; try again"
	case errors.Is(err, context.Canceled):
		return "Stopped"
	}
	var ns *ErrNoSpace
	if errors.As(err, &ns) {
		return ns.Error()
	}
	msg := err.Error()
	if len(msg) > 200 {
		msg = msg[:200]
	}
	return msg
}

// Friendly turns an error into a short sentence for people.
func Friendly(err error) string { return friendly(err) }

// HumanBytes formats a byte count like "1.2 GB".
func HumanBytes(n int64) string {
	const unit = 1000
	if n < unit {
		return fmt.Sprintf("%d B", n)
	}
	div, exp := int64(unit), 0
	for m := n / unit; m >= unit && exp < 4; m /= unit {
		div *= unit
		exp++
	}
	v := float64(n) / float64(div)
	if v < 10 {
		return fmt.Sprintf("%.1f %cB", v, "kMGTP"[exp])
	}
	return fmt.Sprintf("%.0f %cB", v, "kMGTP"[exp])
}
