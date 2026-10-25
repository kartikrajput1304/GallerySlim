# GallerySlim protocol v2 ("gs2") — phone ⇄ laptop receiver

Normative contract between the phone (`LanHttpServer`, Kotlin) and the receiver (`receiver/`, Go).
Test vectors: `receiver/testdata/gs2-vectors.json` (identical copy in `app/src/test/resources/`), generated with
Go's standard crypto. Both sides MUST pass them. Security-sensitive: needs a person's review before release.

Encoding: JSON is UTF-8. Binary values in JSON and the QR use **base64url without padding**. Hashes in JSON are
lower-case hex. Integers in binary structures are **big-endian**.

## 1. Discovery
- Phone listens on the first free port of **8765–8769**, bound to its Wi-Fi address only; requests are accepted only
  from the phone's own Wi-Fi subnet.
- Receiver finds candidates by (a) a one-shot mDNS query for `galleryslim.local` type A sent to 224.0.0.251:5353
  from an ephemeral UDP port (phone answers by unicast), listening 1.5 s for all answers; (b) if none, probing
  `GET /api/hello` on ports 8765–8769 for every address of the laptop's IPv4 subnets with prefix ≥ 24, 300 ms
  timeout; (c) an address the user types (shown on the phone).
- `GET /api/hello` (no auth) → `200`, header `X-GallerySlim: 2`, body
  `{"app":"GallerySlim","proto":2,"name":"<phone model>","code":"K7-42","port":8765}`.
  `code` = short code (2 letters + 2 digits, `-` between) chosen per sharing run, also shown on the phone.
  The receiver MUST treat `name`/`code` as untrusted text (length ≤ 40, strip control/bidi chars, HTML-escape).

## 2. Pairing
1. Receiver makes **K** = 32 random bytes. K MUST never be sent over the network or written to logs.
2. `POST /api/r/session`, body `{"receiver":"<≤40 chars>","os":"windows|mac|linux","folder":"<≤60 chars>"}` →
   `200 {"sid":"<16 chars base64url of 12 random bytes>","n":"<base64url of 32 random bytes>","code":"K7-42"}`.
   The session is pinned to the requesting IP. Body ≤ 16 KiB. At most 32 unapproved sessions (oldest evicted).
3. Receiver shows the QR text **`gs2:<sid>:<base64url(K)>`** and the short code.
4. `GET /api/r/state?sid=<sid>` (no auth, pinned IP) → `{"state":"waiting"|"approved"|"expired"}`. Poll every 1 s.
5. Phone scans `gs2:`, shows receiver name + short code, owner confirms with fingerprint/PIN → approved with the
   chosen file list.

## 3. Keys (HKDF-SHA256, RFC 5869)
`salt = UTF8(sid) ‖ N`, `IKM = K`.
`macKey = HKDF(salt, K, info="gs2 mac", 32 bytes)`, `encKey = HKDF(salt, K, info="gs2 enc", 32 bytes)`.

## 4. Authenticated requests (every `/api/r/*` except `session` and `state`)
Headers: `X-GS-Sid: <sid>`, `X-GS-Ctr: <decimal counter>`, `X-GS-Mac: <base64url(HMAC-SHA256(macKey, canonical))>`.

```
canonical = METHOD "\n" RAWTARGET "\n" RANGE "\n" SID "\n" CTR "\n" hex(SHA-256(body))
```
- `RAWTARGET` = path plus `?query` exactly as sent on the request line (e.g. `/api/r/file?f=42`).
- `RANGE` = the `Range` header value, or empty.
- Empty body hashes to `e3b0c442…b855`.
- Counter: written in decimal with no sign and no leading zeros (in both request and answer signatures).
  Starts at 1, increases by 1 per request. Parallel streams can arrive out of order, so the phone keeps
  a window: accept if `ctr > highest − 1024` and `ctr` not seen before; else reject.
- Failure → `401 {"error":"auth"}`. Wrong IP → `403`. Unknown/expired session → `410 {"error":"expired"}`.

### 4a. Signed answers (added after security review)
Every non-encrypted answer to an **authenticated** request (ack, done, and error answers such as 404/409/416 on
authenticated routes) carries
`X-GS-RMac: base64url(HMAC-SHA256(macKey, "resp\n" CTR "\n" STATUS "\n" hex(SHA-256(body))))`, where CTR is the
counter of the request it answers and STATUS the decimal HTTP status. The receiver MUST verify it and treat an answer
without a valid RMac as a network error (retry), never as a decision. In particular the receiver never deletes or
replaces a finished, re-hashed file because of any answer: if the phone says "not verified", it keeps the file and
saves any new attempt under a new name. 401/403/410 answers (no valid session) are unsigned and only mean "pair again".

## 5. Encrypted responses
`GET /api/r/list` and `GET /api/r/file` return `application/octet-stream` with header `X-GS-Enc: 1`:

```
stream = PREFIX(8 random bytes, new per response) ‖ frame*
frame  = LEN(uint32, = ciphertext length incl. 16-byte tag) ‖ AES-256-GCM(encKey, nonce, plaintext, aad)
nonce  = PREFIX ‖ uint32(frame index, from 0)
aad    = "gs2" ‖ UTF8(sid) ‖ 0x00 ‖ UTF8(resource) ‖ 0x00 ‖ uint64(absolute offset of frame's first byte) ‖ LAST(1 byte: 1 last, else 0)
```
- Plaintext per frame: **exactly 1 MiB (1,048,576 bytes)** for every frame except the last, which may be shorter
  (a short non-last frame is an error); an empty resource is one empty frame with LAST=1. (Vectors use a frame size
  of 8 for brevity.)
- `resource` = `"list"` for the list, `"f:" + fileId` for files.
- The stream is complete only after a frame with LAST=1. Anything else (closed early, bad tag) is an error.

## 6. Endpoints
- File ids are `[A-Za-z0-9_-]{1,64}` (the phone uses MediaStore ids), so `f=<id>` needs no percent-encoding and the
  resource name `f:<id>` is the id as-is.
- `GET /api/r/list` → encrypted JSON
  `{"files":[{"id":"…","name":"…","size":123,"mime":"image/jpeg","taken":1700000000000,"month":"2023-11","sha256":"hex or null"}]}`.
  `size` is the file's real current length (not a stored estimate). `month` is `yyyy-MM` or `undated`.
- `GET /api/r/file?f=<id>` with optional `Range: bytes=<start>-` (open-ended only) → `200` (or `206` with a range),
  encrypted plaintext from `start` to the end of the file; frame offsets are absolute. If the file's length no
  longer matches the list, or it changed, the phone answers `409 {"error":"changed"}` before streaming, or ends the
  stream without a LAST frame if it changes mid-way.
- `POST /api/r/ack`, body `{"f":"<id>","sha256":"<hex>","size":<n>}` → `{"ok":true,"verified":true|false}` (signed, § 4a).
  The receiver sends it only after re-reading the saved file from its own disk. It is also used for files the
  receiver already had ("verify only"). The phone compares with its own SHA-256 of the **whole current file**.
- `POST /api/r/done` → `{"ok":true}` (signed); ends the session.
- `Range` values that are not `bytes=<start>-` with a start that fits in 63 bits → `416` (signed).

## 7a. Status codes and edge cases
- `200`/`206` encrypted streams; `400` malformed request or ack; `401` bad signature/counter or session not yet
  approved; `403` wrong address; `404` unknown file or route; `409` file changed since the list; `410` session
  unknown/expired; `413` body too large; `416` malformed Range or start beyond the end; `503` busy (connection
  limits, sent before authentication, **unsigned**: only means wait 2 s and retry).
- A Range start equal to the file size is allowed and returns one empty LAST frame (the receiver never needs it:
  for a complete `.part` it re-requests the final byte so it always sees a LAST frame).
- If a Range was sent but the answer is `200`, the plaintext starts at 0; the receiver discards its partial copy.
- `GET /api/r/state` answers are unsigned (a forged "expired" can only delay pairing).
- Receiver limits: list ≤ 64 MiB, ≤ 200,000 files, ids ≤ 64 characters.

## 8. Known, accepted limits (security review)
- Frame AAD does not include the request counter, so an attacker in the middle could replay an earlier authentic
  response for the same file and offset; the whole-file SHA-256 check in `ack` catches any resulting mismatch.
- The short code distinguishes phones; it does not authenticate the laptop. The fingerprint step + the QR (seen only
  by the camera) is what authorises a laptop.

## 7. Limits and lifetime
Session idle timeout 10 min, refreshed on every request **and on every frame sent**. At most 3 concurrent streams per
session from the receiver; on `503` the receiver waits 2 s and retries (max 5 times).


## 9. Remembered pairing ("Your computers", added 2026-10-25)
Goal: after one QR pairing, later sends need only the owner's fingerprint on the phone. Security-sensitive.

- **Ids.** The phone has a random phone id `pid` (16 random bytes, base64url) created once per install; `/api/hello`
  adds `"pid":"<pid>"`. The receiver has a random receiver id `rid` (same format) created once per install.
- **Pair key.** When a QR-paired session (§2) is approved, both sides derive
  `PK = HKDF(salt = UTF8(sid) ‖ N, IKM = K, info = "gs2 pair", 32 bytes)` and store it: the phone keyed by `rid`
  (app-private storage) with the receiver's name; the receiver keyed by `pid` (config dir, file mode 0600) with the
  phone's name. The session request (§2.2) gains `"rid":"<rid>"`; the phone stores PK only if `rid` is present.
  PK is never sent over the network or logged.
- **Known session.** `POST /api/r/session` with `"rid"` of a remembered receiver returns, in addition,
  `"known":true`. The receiver then proves possession:
  `POST /api/r/claim` body `{"sid":"…","rid":"…","proof":"<base64url(HMAC-SHA256(PK, "gs2 claim\n" + sid + "\n" + base64url(N)))>"}`
  → `200 {"ok":true}` or `401`. A claimed session is listed on the phone as "<receiver name> is ready".
- **Approval.** The owner taps the computer on the phone and confirms with fingerprint/PIN (no QR). Session keys are
  `HKDF(salt = UTF8(sid) ‖ N, IKM = PK, info = "gs2 mac" / "gs2 enc")`; everything else (§4–§7) is unchanged. The
  receiver polls `/api/r/state` as in §2.4.
- **Forget.** Either side deletes its PK; the phone answers a claim with an unknown or forgotten `rid` with `401`
  and the receiver falls back to QR pairing.
- **Discovery while idle.** A receiver with remembered phones may probe the last known address and send the mDNS
  query (§1) every 5 s while running; probing stops while a transfer runs.


## 10. Bring back to phone (added 2026-10-25)
Direction reversed: the receiver uploads files to the phone. The phone stays the server; everything is inside an
approved gs2 session (QR-paired or remembered, §2/§9) and uses that session's keys.

- **Phone modes.** The phone accepts uploads only while its "Bring back" or "Receive from computer" screen is open
  (`ShareHub` flag); otherwise upload routes answer `403 {"error":"not receiving"}` (signed).
- **Wanted list.** `GET /api/r/wants` (authenticated) → encrypted (§5, resource `"wants"`) JSON
  `{"files":[{"id":"…","name":"…","size":123,"sha256":"hex","month":"2024-03","taken":ms}]}` — the moved files the
  owner picked in "Moved to computer" (from history.db MOVED/MOVED_PURGED/VERIFIED rows with sha256). Empty when the
  owner chose "Receive from computer" (free choice on the computer).
- **Offer** (free choice only). `POST /api/r/offer` body `{"files":[{"name":"…","size":n,"sha256":"hex","mime":"image/jpeg","taken":ms}]}`
  (≤ 500 files, ≤ 16 KiB) → signed `{"ok":true,"accepted":["o1","o2",…],"refused":[{"name":"…","why":"…"}]}`; the
  phone refuses non-image/video mimes and anything beyond free space minus 500 MB; accepted files get ids `o<n>`.
- **Upload.** `POST /api/r/upload?f=<id>` (authenticated; `canonical` uses `hex(SHA-256(""))` as the body hash because
  the body is streamed — the integrity check is the frames + final hash), `Content-Length` = stream length, body =
  a gs2 frame stream (§5) with resource `"u:" + id`, offset 0, encrypted with the session `encKey`. The phone decrypts
  frame by frame into a MediaStore pending item (`Pictures/GallerySlim/Restored/<month>` for images,
  `Movies/GallerySlim/Restored/<month>` for videos; MediaStore picks a free name, nothing is overwritten), hashes
  the plaintext, and on the LAST frame checks size and SHA-256 against the wanted/offered entry; only then sets
  DATE_TAKEN and clears IS_PENDING. Any mismatch, truncation or bad frame deletes the pending item.
  Answer (signed): `{"ok":true,"saved":true,"uri":"…"}` or `{"ok":false,"why":"…"}`. One upload at a time.
- **After.** The phone marks matching history rows "back on phone" and shows the result; the receiver page shows
  ✓ per file. A file the receiver can't find (moved/deleted on the computer, or its on-disk SHA-256 differs) is
  reported "Not found on this computer" and skipped.
- **Limits.** Upload stream ≤ 4 GiB per file; per-file stall 30 s (same watchdog); free-space re-check before each
  file.
