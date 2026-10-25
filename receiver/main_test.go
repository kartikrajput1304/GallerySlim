package main

import (
	"bytes"
	"testing"

	"galleryslim/receiver/internal/app"
)

func TestFlags(t *testing.T) {
	t.Setenv("XDG_CONFIG_HOME", t.TempDir()) // keep the real settings and log untouched
	var out, errb bytes.Buffer
	if code := run([]string{"--version"}, &out, &errb); code != 0 || out.String() != "dev\n" {
		t.Errorf("version: %d %q", code, out.String())
	}
	if code := run([]string{"--headless"}, &out, &errb); code != app.ExitUsage {
		t.Errorf("headless without folder: %d", code)
	}
	if code := run([]string{"stray"}, &out, &errb); code != app.ExitUsage {
		t.Errorf("stray argument: %d", code)
	}
	if code := run([]string{"--no-such-flag"}, &out, &errb); code != app.ExitUsage {
		t.Errorf("unknown flag: %d", code)
	}
}

func TestToSnapshot(t *testing.T) {
	s := app.Status{
		Phase: app.PhaseFinding, Folder: "/p", PhoneName: "Pixel <8>", Code: "K7-42", QRSVG: "<svg/>",
		Phones:    []app.Phone{{Name: "A", Code: "K7-42", Addr: "10.0.0.2:8765"}},
		FilesDone: 1, FilesTotal: 2, BytesDone: 3, BytesTotal: 4, Current: "c", Error: "e", Version: "v",
		Rows: []app.Row{{ID: "1", Name: "2023-11/a.jpg", Size: 5, State: app.RowVerified, Detail: "d"}},
	}
	snap := toSnapshot(s)
	if snap.Phase != s.Phase || snap.PhoneName != s.PhoneName || snap.QRSVG != s.QRSVG || len(snap.Phones) != 1 ||
		snap.Phones[0].Addr != "10.0.0.2:8765" || len(snap.Rows) != 1 || snap.Rows[0].State != "verified" ||
		snap.BytesTotal != 4 || snap.Version != "v" || snap.Error != "e" {
		t.Errorf("%+v", snap)
	}
}

func TestToSnapshotRememberedAndBack(t *testing.T) {
	s := app.Status{
		Phase: app.PhaseFinding, Waiting: true, ComputerName: "Kartik's PC", Direction: app.DirToPhone, PhoneReceiving: true,
		Paired: []app.PairedPhone{{ID: "x", Name: "Galaxy S24", LastUsed: 1700000000000}, {ID: "y", Name: "Pixel", Paired: 1600000000000}},
		Rows:   []app.Row{{ID: "w1", Name: "a.jpg", State: app.RowSent, Preview: true}, {ID: "w2", Name: "b.jpg", State: app.RowNotFound, Detail: "Not found on this computer"}},
	}
	snap := toSnapshot(s)
	if snap.ComputerName != "Kartik's PC" || snap.WaitingFor != "Galaxy S24" || snap.Direction != "to-phone" || !snap.PhoneReceiving {
		t.Errorf("%+v", snap)
	}
	if len(snap.PairedPhones) != 2 || snap.PairedPhones[0].LastSeen != "2023-11-14T22:13:20Z" || snap.PairedPhones[1].LastSeen != "2020-09-13T12:26:40Z" {
		t.Errorf("paired %+v", snap.PairedPhones)
	}
	if !snap.Rows[0].Thumb || snap.Rows[1].Thumb || snap.Rows[1].State != "not-found" {
		t.Errorf("rows %+v", snap.Rows)
	}
	s.Waiting, s.Remembered, s.PhoneName = false, true, "Pixel"
	if toSnapshot(s).WaitingFor != "Pixel" {
		t.Error("waiting for the claimed phone")
	}
	if tip := trayTip(app.Status{Phase: app.PhaseReceiving, FilesDone: 2, FilesTotal: 5}); tip != "GallerySlim Receiver — receiving 2 of 5" {
		t.Errorf("tip %q", tip)
	}
	if tip := trayTip(app.Status{}); tip != "GallerySlim Receiver — ready" {
		t.Errorf("tip %q", tip)
	}
}
