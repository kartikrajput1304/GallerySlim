package app

import (
	"context"
	"errors"
	"fmt"
	"io"
	"sync"
	"time"

	"galleryslim/receiver/internal/client"
	"galleryslim/receiver/internal/discover"
	"galleryslim/receiver/internal/qr"
	"galleryslim/receiver/internal/receive"
	"galleryslim/receiver/internal/store"
)

// Exit codes of headless mode.
const (
	ExitOK      = 0 // every file verified (or no longer on the phone)
	ExitFailed  = 1 // something failed or could not be confirmed
	ExitUsage   = 2 // bad command line
	maxRepairs  = 3
	progressGap = 5 * time.Second
)

// HeadlessOptions configures a terminal-only run.
type HeadlessOptions struct {
	Deps
	Folder  string
	Phone   string // ip[:port], optional
	PlainQR bool   // no ANSI colours (assumes light text on a dark terminal)
	Out     io.Writer
}

// RunHeadless receives once in the terminal and returns the process exit code.
func RunHeadless(ctx context.Context, o HeadlessOptions) int {
	out := &lineWriter{w: o.Out}
	if o.Folder == "" {
		out.printf("Please choose a folder: --folder <path>\n")
		return ExitUsage
	}
	if err := store.CheckWritable(o.Folder); err != nil {
		out.printf("Photos can't be saved in %s: %v\n", o.Folder, err)
		return ExitUsage
	}
	if o.CloudSynced != nil {
		if synced, provider := o.CloudSynced(o.Folder); synced {
			out.printf("Note: %s syncs to %s; photos there may be moved off this computer.\n", o.Folder, provider)
		}
	}
	out.printf("GallerySlim Receiver %s, saving to %s\n", o.Version, o.Folder)

	var cand discover.Candidate
	if o.Phone != "" {
		c, err := discover.ProbeAddress(ctx, o.Phone, o.Probe)
		if err != nil {
			out.printf("%v\n", err)
			return ExitFailed
		}
		cand = c
	} else {
		out.printf("Looking for your phone (open GallerySlim and tap \"Send to computer\")…\n")
		cands, _ := o.Find(ctx)
		switch {
		case ctx.Err() != nil:
			return ExitFailed
		case len(cands) == 0:
			out.printf("No phone found. Run again with --phone <address shown on the phone>.\n")
			return ExitFailed
		case len(cands) > 1:
			out.printf("Found several phones; run again with --phone and one of these addresses:\n")
			for _, c := range cands {
				out.printf("  %s  %s (code %s)\n", c.Addr, c.Name, c.Code)
			}
			return ExitUsage
		}
		cand = cands[0]
	}
	out.printf("Found %s (code %s) at %s\n", cand.Name, cand.Code, cand.Addr)

	folder, err := store.Open(o.Folder)
	if err != nil {
		out.printf("Can't use the folder: %v\n", err)
		return ExitFailed
	}
	folder.CleanupParts(14 * 24 * time.Hour)

	for attempt := 0; attempt < maxRepairs; attempt++ {
		sess, err := receive.Pair(ctx, receive.PairOptions{
			HTTP: o.HTTP, Addr: cand.Addr, Config: o.Client, PollEvery: o.PollEvery,
			Info: client.SessionInfo{Receiver: o.Hostname, OS: o.OSName, Folder: baseName(o.Folder)},
			ShowQR: func(text, code string) {
				c, err := qr.Encode([]byte(text))
				if err != nil {
					out.printf("Could not draw the code: %v\n", err)
					return
				}
				out.printf("\nScan this with GallerySlim on your phone. Check that the phone shows the code %s.\n\n", code)
				out.printf("%s\n", c.Terminal(!o.PlainQR))
				out.printf("Waiting for you to confirm on the phone…\n")
			},
		})
		if err != nil {
			out.printf("Pairing failed: %v\n", receive.Friendly(err))
			return ExitFailed
		}
		ev := &printer{out: out}
		sum, err := receive.Run(ctx, receive.Options{
			Session: sess, Folder: folder, Phone: cand.Name, Events: ev, KeepAwake: o.KeepAwake,
		})
		if errors.Is(err, client.ErrExpired) || errors.Is(err, client.ErrAuth) || errors.Is(err, client.ErrForbidden) {
			out.printf("The phone ended the session (%s). Scan the new code to continue; saved files are kept.\n", receive.Friendly(err))
			continue
		}
		if err != nil {
			out.printf("Stopped: %s\n", receive.Friendly(err))
			return ExitFailed
		}
		out.printf("Done: %d verified (%d were already here), %d saved but not confirmed, %d no longer on the phone, %d failed.\n",
			sum.Verified, sum.Already, sum.Saved, sum.Skipped, sum.Failed)
		if sum.Failed > 0 || sum.Saved > 0 {
			return ExitFailed
		}
		return ExitOK
	}
	out.printf("Gave up after %d sessions.\n", maxRepairs)
	return ExitFailed
}

func baseName(p string) string {
	for i := len(p) - 1; i >= 0; i-- {
		if p[i] == '/' || p[i] == '\\' {
			return p[i+1:]
		}
	}
	return p
}

type lineWriter struct {
	mu sync.Mutex
	w  io.Writer
}

func (l *lineWriter) printf(format string, a ...any) {
	l.mu.Lock()
	defer l.mu.Unlock()
	fmt.Fprintf(l.w, format, a...)
}

// printer prints one line per file and a progress line every few seconds.
type printer struct {
	out   *lineWriter
	mu    sync.Mutex
	total int
	done  int
	bytes int64
	all   int64
	last  time.Time
}

func (p *printer) Listed(files []client.File, total int64) {
	p.mu.Lock()
	p.total, p.all, p.last = len(files), total, time.Now()
	p.mu.Unlock()
	p.out.printf("Receiving %d files (%s)…\n", len(files), receive.HumanBytes(total))
}

func (p *printer) FileStarted(client.File, string) {}

func (p *printer) Progress(d int64) {
	p.mu.Lock()
	p.bytes += d
	show := time.Since(p.last) >= progressGap
	if show {
		p.last = time.Now()
	}
	b, all := p.bytes, p.all
	p.mu.Unlock()
	if show && all > 0 {
		p.out.printf("  %s of %s (%d%%)\n", receive.HumanBytes(b), receive.HumanBytes(all), b*100/all)
	}
}

func (p *printer) FileDone(r receive.Result) {
	p.mu.Lock()
	p.done++
	n, total := p.done, p.total
	p.mu.Unlock()
	name := r.Name
	if r.Rel != "" {
		name = r.Rel
	}
	line := fmt.Sprintf("[%d/%d] %-8s %s", n, total, r.Outcome, name)
	if r.Detail != "" {
		line += " — " + r.Detail
	}
	p.out.printf("%s\n", line)
}
