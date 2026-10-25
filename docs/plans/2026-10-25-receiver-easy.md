# Receiver: easy to get, easy to use (2026-10-25)

## Research (what the best transfer apps do)
- **Zero setup, one decision** (AirDrop): pick the target, the other side accepts; no accounts, no servers.
- **Friendly device names** (LocalSend shows an auto-generated name on the Receive screen; users rename to "Desktop PC").
- **Your devices vs. everyone** (Quick Share visibility: Everyone / Contacts / Your devices / No one): remember trusted
  devices so later transfers need no codes; "everyone" is temporary.
- **Short code check when pairing** (Quick Share PIN match) — but don't add friction every time.
- **Honest progress** (upload-UX guidance): real-time bar, how far, roughly how long, errors in place with next steps;
  drag-and-drop plus a browse button.

## What we build
1. **Download from the phone's page.** `galleryslim.local:8765` detects the computer's system and shows one button:
   "Download GallerySlim Receiver for Windows" (also Mac / Linux links). The app bundles the receiver archives
   (Windows x64 zip, macOS arm64 + x64 zips, Linux x64 tar.gz; ≈13 MB). Served over local Wi-Fi only.
2. **Pair once ("Your computers").** First time: QR + short code + fingerprint as now. The phone and receiver then
   remember each other. Later: open Send to computer on the phone → a running receiver finds the phone by itself →
   the phone shows "Send to Kartik's PC" → tap → fingerprint → done. "Forget" on both sides. (Protocol: gs2 §9.)
3. **Receiver runs quietly.** After first run it offers "Start with this computer" (Windows Run key, macOS
   LaunchAgent, Linux autostart .desktop) and "Install" (copy to a stable place + Start menu / Applications entry).
   Windows shows a tray icon ("Ready to receive from Galaxy S24" · Open · Quit). The page opens by itself when a
   transfer starts.
4. **A calmer, friendlier receiver page**: one screen with a device card, big progress ring, thumbnails of what
   arrived, "Open folder", clear errors with a fix; settings for folder, computer name, paired phones, start at login.
5. **Moved to computer** also lists photos verified on a computer that are still on the phone ("On your computer").

Signing (Windows/macOS) remains an RxLogix IT decision; until then first-run warnings appear.
