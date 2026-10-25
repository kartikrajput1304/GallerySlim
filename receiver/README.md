# GallerySlim Receiver

A small program for Windows, macOS and Linux that copies photos from the GallerySlim phone app over
Wi-Fi into one folder you choose, sorted into month folders. Nothing else needs to be installed.

A photo counts as safe on the computer only after the receiver has read it back from its own disk and
the phone has confirmed that the bytes match its own copy ("verified").

> **Unsigned test builds.** Code signing (Windows) and signing + notarisation (macOS) are pending
> company IT. Until then the operating system will warn about, or block, these builds. See
> "First run" below.

## Which file do I need?

| Computer | File |
|---|---|
| Windows 10/11, most PCs | `windows-amd64/GallerySlim-Receiver.exe` |
| Windows on ARM (Snapdragon) | `windows-arm64/GallerySlim-Receiver.exe` |
| Mac with Apple silicon (M1 and later) | `GallerySlim-Receiver-macos-arm64.zip` |
| Mac with Intel processor | `GallerySlim-Receiver-macos-amd64.zip` |
| Linux PC | `GallerySlim-Receiver-linux-amd64.tar.gz` |
| Linux on ARM (e.g. Raspberry Pi 4/5, 64-bit) | `GallerySlim-Receiver-linux-arm64.tar.gz` |

`SHA256SUMS` lists the checksum of every file.

Minimum systems: Windows 10, **macOS 13 (Ventura)** (the Go 1.27 toolchain used for these builds does
not support older macOS), any 64-bit Linux from the last several years.

## How to use

1. Open the receiver (see your system below). It opens a page in your browser. The page lives only on
   your own computer (`127.0.0.1`); keep the receiver running while you use it.
2. First time: **Choose folder**. The suggestion is a folder that is not synced to OneDrive, iCloud
   Drive or Google Drive; if you pick a synced folder the page says so, because synced photos may be
   moved off the computer and can then no longer be checked.
3. On the phone: pick photos, tap **Send to computer**.
4. The receiver finds the phone and shows a QR code and a short code such as `K7-42`. Scan the QR code
   with GallerySlim, check that the phone shows the same short code, and confirm with your fingerprint.
5. Watch the progress. The computer is kept awake while receiving. When it is done, use
   **Open folder** to see the photos.

If the Wi-Fi drops or the laptop sleeps, open the receiver again and scan again: finished files are
kept and half-received files continue where they stopped.

### Paired phones ("Your computers")

After the first QR pairing the phone and the receiver remember each other. Next time, a running
receiver finds the phone by itself and the phone shows "Send to <computer name>": tap it and confirm
with your fingerprint, no code to scan. The computer name can be changed in the page's settings.
**Forget** (on the page, or on the phone) removes the pairing; the next time the QR code is used again.

### Running quietly ("Start with this computer")

The page offers **Start with this computer**. The receiver then starts at login with `--background`:
no page, no window; it looks for paired phones every 5 seconds (their last address and one multicast
DNS question, not while files are moving) and opens the page by itself when files start arriving. In
this mode it does not quit by itself. On Windows it shows a tray icon (Open · Start with Windows ·
Quit). Where it is registered:

| System | Start at login | Install |
|---|---|---|
| Windows | `HKCU\Software\Microsoft\Windows\CurrentVersion\Run`, value "GallerySlim Receiver" | `%LOCALAPPDATA%\Programs\GallerySlim Receiver\` + Start-menu shortcut |
| macOS | `~/Library/LaunchAgents/com.galleryslim.receiver.plist` | `~/Applications/GallerySlim Receiver.app` |
| Linux | `~/.config/autostart/gallery-slim-receiver.desktop` | `~/.local/bin/gallery-slim-receiver` + `~/.local/share/applications/gallery-slim-receiver.desktop` |

**Install on this computer** copies the program to the place above (no administrator rights needed),
so it keeps working after the download is deleted. Only one receiver runs per user: starting it again
opens the running one's page.

### Bring back to phone

When the phone shows "Bring back" (photos you moved to this computer) or "Receive from computer", the
receiver sends files the other way. For "Bring back" it finds each photo in your folder by its
checksum (also when it was moved inside the folder), re-checks it on disk and sends it; photos it can't
find with the same bytes are listed as "Not found on this computer". For "Receive from computer" pick
files on the page (or drop them on it). The phone checks every file's size and checksum before keeping it.

### Windows

Double-click `GallerySlim-Receiver.exe`. A small console window opens next to the browser page. That
window *is* the receiver: keep it open while receiving and close it to stop.

Why a console window: the program is built as a normal console program rather than a hidden one
(`-H windowsgui`). This keeps a visible, obvious way to stop it, lets it print the page address if the
browser does not open, and makes `--headless` work in a terminal. A console-less build would need extra
work to show errors and to quit, so it is not used yet.

First run on unsigned builds:
- **SmartScreen** ("Windows protected your PC"): click **More info**, then **Run anyway**.
- **Smart App Control** (Windows 11, if turned on) blocks unsigned programs and offers no "Run anyway".
  Such PCs need the signed build.
- Antivirus programs may question an unsigned program that talks to the network.
- Windows Firewall may ask about network access the first time; allow **private** networks.

### macOS

1. Unzip, then move **GallerySlim Receiver** to Applications.
2. Open it. Because it is not yet signed and notarised:
   - macOS 13–14: right-click (or Control-click) the app, choose **Open**, then **Open** again.
   - macOS 15 and later: open it once, close the warning, then go to **System Settings → Privacy &
     Security**, scroll down and click **Open Anyway** next to GallerySlim Receiver.
   - If macOS says the app "is damaged", the download was quarantined; in Terminal run
     `xattr -dr com.apple.quarantine "/Applications/GallerySlim Receiver.app"` and open it again.
3. Allow **Local Network** access when asked; without it the receiver cannot see the phone. You can
   change this later in **System Settings → Privacy & Security → Local Network**.

The app has no Dock icon; it shows its page in the browser. Use **Close the receiver** on the page to
quit (it also quits by itself after 30 minutes with the page closed and nothing being received, except when started with `--background`).

### Linux

```sh
tar xzf GallerySlim-Receiver-linux-amd64.tar.gz
cd GallerySlim-Receiver-linux-amd64
./GallerySlim-Receiver
```

The folder dialog uses `zenity` or `kdialog` when installed; otherwise type the folder path on the page.
Keeping the computer awake uses `systemd-inhibit` when available.

Without a desktop (no `DISPLAY`/`WAYLAND_DISPLAY`) or with `--headless`, the receiver runs in the
terminal and prints the QR code as text:

```sh
./GallerySlim-Receiver --headless --folder ~/Pictures/Phone
./GallerySlim-Receiver --headless --folder ~/Pictures/Phone --phone 192.168.1.23:8765
```

Exit codes: `0` every file verified (or no longer on the phone), `1` something failed or could not be
confirmed, `2` bad command line or several phones found (choose one with `--phone`).

## Options

| Option | Meaning |
|---|---|
| `--folder <path>` | Save into this folder (also remembered for next time in page mode). |
| `--phone <ip[:port]>` | Use the address shown on the phone instead of searching. |
| `--headless` | Terminal only. |
| `--no-browser` | Don't open the browser; the page address is printed instead. |
| `--plain-qr` | Terminal QR without colours (default on Windows). |
| `--background` | Run quietly and receive from paired phones when they send (used by "Start with this computer"). |
| `--version` | Print the version. |

## Where things go

- Photos: `<your folder>/<yyyy-MM>/<name>`, or `<your folder>/undated/<name>` when the date is unknown.
  The modification time is set to the time the photo was taken.
- Existing files are never overwritten. A second file with the same name becomes `IMG_1 (2).jpg`
  (on Windows and macOS, names that differ only in upper/lower case count as the same).
- File names are cleaned so they work on every system (for example `CON.jpg` becomes `_CON.jpg`,
  and `<>:"/\|?*` become `_`).
- `<your folder>/.gallery-slim/` holds the receiver's own files: `parts/` (half-received files, removed
  after 14 days) and `manifest.json` (what was saved; only a hint, files are always re-checked).
- Settings (`config.json`: the folder, computer name, this receiver's random id and window
  preferences), paired phones (`pairs.json`: each phone's id, name, last address and pairing key,
  file mode 0600) and the log (`receiver.log`, no keys or codes) are in your user settings folder
  under `GallerySlim Receiver`: `%AppData%` on Windows, `~/Library/Application Support` on macOS,
  `~/.config` on Linux. Deleting `pairs.json` forgets every phone.

## Network and privacy

- The receiver only connects *to* the phone; it opens no network port. Its page listens on
  `127.0.0.1` only, with a random port and a random secret in the address.
- To find the phone it sends one multicast DNS question (`galleryslim.local`) and listens 1.5 s for
  answers, then tries the phone's ports (8765–8769) on your local network (`/24` or smaller), or uses
  the address you type.
- Pairing uses a secret made on the computer and shown only inside the QR code; it is never sent over
  the network or written to disk. A paired phone and computer each keep a pairing key derived from it
  (`pairs.json` above); it is never sent either: the computer proves it has the key, and the phone's
  owner still confirms every transfer with a fingerprint. Requests are signed and files arrive encrypted (protocol
  `docs/protocol-gs2.md`).

## Building

Needs Go 1.24 or newer (built and tested with Go 1.27.3). No other tools or modules (standard library
only) except `zip` and `tar` for packaging.

```sh
./build.sh            # outputs into ../dist/receiver/
go vet ./... && go test -race ./...
```

`VERSION=0.2.0 ./build.sh` sets the version. Builds are unsigned.
