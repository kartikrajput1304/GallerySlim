// Package gs2 implements the cryptographic parts of the GallerySlim protocol v2
// (docs/protocol-gs2.md): key derivation, request MACs and the framed AES-GCM
// response stream.
//
// Security-sensitive: needs a person's review before release.
package gs2

import (
	"crypto/hkdf"
	"crypto/hmac"
	"crypto/sha256"
	"encoding/base64"
	"encoding/binary"
	"encoding/hex"
	"errors"
	"fmt"
	"strconv"
	"strings"
)

const (
	// FrameSize is the plaintext size of every frame except the last (1 MiB).
	FrameSize = 1 << 20
	// TagSize is the AES-GCM tag length.
	TagSize = 16
	// PrefixSize is the length of the random per-response nonce prefix.
	PrefixSize = 8
	// KeySize is the size of K, the pairing secret, and of the derived keys.
	KeySize = 32
	// QRPrefix starts the pairing QR text.
	QRPrefix = "gs2:"
)

// B64 is base64url without padding, used for all binary values in JSON, headers and the QR.
var B64 = base64.RawURLEncoding

// Keys are the per-session keys derived from K.
type Keys struct {
	Mac [32]byte
	Enc [32]byte
}

// DeriveKeys derives the session keys: HKDF-SHA256 with salt = UTF8(sid) || N, IKM = K.
func DeriveKeys(sid string, n, k []byte) (Keys, error) {
	var keys Keys
	if len(k) != KeySize {
		return keys, errors.New("gs2: K must be 32 bytes")
	}
	if len(n) != 32 {
		return keys, errors.New("gs2: N must be 32 bytes")
	}
	if !ValidSid(sid) {
		return keys, errors.New("gs2: bad sid")
	}
	salt := make([]byte, 0, len(sid)+len(n))
	salt = append(salt, sid...)
	salt = append(salt, n...)
	mk, err := hkdf.Key(sha256.New, k, salt, "gs2 mac", 32)
	if err != nil {
		return keys, err
	}
	ek, err := hkdf.Key(sha256.New, k, salt, "gs2 enc", 32)
	if err != nil {
		return keys, err
	}
	copy(keys.Mac[:], mk)
	copy(keys.Enc[:], ek)
	clear(mk)
	clear(ek)
	return keys, nil
}

// ValidSid reports whether sid is 16 base64url characters that decode to 12 bytes.
func ValidSid(sid string) bool {
	if len(sid) != 16 {
		return false
	}
	b, err := B64.DecodeString(sid)
	return err == nil && len(b) == 12
}

// QRText is the pairing text shown as a QR code: gs2:<sid>:<base64url(K)>.
func QRText(sid string, k []byte) string {
	return QRPrefix + sid + ":" + B64.EncodeToString(k)
}

// ParseQRText splits a gs2 QR text into sid and K (used by tests and the fake phone).
func ParseQRText(s string) (sid string, k []byte, err error) {
	rest, ok := strings.CutPrefix(s, QRPrefix)
	if !ok {
		return "", nil, errors.New("gs2: not a gs2 code")
	}
	sid, kb64, ok := strings.Cut(rest, ":")
	if !ok || !ValidSid(sid) {
		return "", nil, errors.New("gs2: bad sid in code")
	}
	k, err = B64.DecodeString(kb64)
	if err != nil || len(k) != KeySize {
		return "", nil, errors.New("gs2: bad key in code")
	}
	return sid, k, nil
}

// BodyHashHex is the lower-case hex SHA-256 of a request body (empty body included).
func BodyHashHex(body []byte) string {
	h := sha256.Sum256(body)
	return hex.EncodeToString(h[:])
}

// Canonical builds the string covered by a request MAC:
// METHOD \n RAWTARGET \n RANGE \n SID \n CTR \n hex(SHA-256(body)).
// ctr is written in plain decimal without leading zeros.
func Canonical(method, rawTarget, rangeHeader, sid string, ctr uint64, body []byte) string {
	var b strings.Builder
	b.Grow(len(method) + len(rawTarget) + len(rangeHeader) + len(sid) + 90)
	b.WriteString(method)
	b.WriteByte('\n')
	b.WriteString(rawTarget)
	b.WriteByte('\n')
	b.WriteString(rangeHeader)
	b.WriteByte('\n')
	b.WriteString(sid)
	b.WriteByte('\n')
	b.WriteString(strconv.FormatUint(ctr, 10))
	b.WriteByte('\n')
	b.WriteString(BodyHashHex(body))
	return b.String()
}

// MAC returns base64url(HMAC-SHA256(macKey, canonical)).
func MAC(macKey []byte, canonical string) string {
	m := hmac.New(sha256.New, macKey)
	m.Write([]byte(canonical))
	return B64.EncodeToString(m.Sum(nil))
}

// VerifyMAC checks a base64url MAC in constant time.
func VerifyMAC(macKey []byte, canonical, mac string) bool {
	got, err := B64.DecodeString(mac)
	if err != nil {
		return false
	}
	m := hmac.New(sha256.New, macKey)
	m.Write([]byte(canonical))
	return hmac.Equal(got, m.Sum(nil))
}

// AAD builds the additional authenticated data of one frame:
// "gs2" || UTF8(sid) || 0x00 || UTF8(resource) || 0x00 || uint64(offset) || LAST.
func AAD(dst []byte, sid, resource string, offset uint64, last bool) []byte {
	dst = append(dst[:0], "gs2"...)
	dst = append(dst, sid...)
	dst = append(dst, 0)
	dst = append(dst, resource...)
	dst = append(dst, 0)
	dst = binary.BigEndian.AppendUint64(dst, offset)
	if last {
		dst = append(dst, 1)
	} else {
		dst = append(dst, 0)
	}
	return dst
}

// FileResource is the resource name of a file in AADs.
func FileResource(id string) string { return "f:" + id }

// ListResource is the resource name of the file list.
const ListResource = "list"

// Errors returned by the decoder. All of them mean the stream must not be trusted past the
// last frame already returned.
var (
	ErrTampered  = errors.New("gs2: frame failed authentication")
	ErrNoLast    = errors.New("gs2: stream ended before its last frame")
	ErrMalformed = errors.New("gs2: malformed stream")
)

func malformed(format string, a ...any) error {
	return fmt.Errorf("%w: %s", ErrMalformed, fmt.Sprintf(format, a...))
}

// RespCanonical is the string covered by a signed answer (§4a):
// "resp" \n CTR \n STATUS \n hex(SHA-256(body)), where CTR is the counter of the request answered.
func RespCanonical(ctr uint64, status int, body []byte) string {
	return "resp\n" + strconv.FormatUint(ctr, 10) + "\n" + strconv.Itoa(status) + "\n" + BodyHashHex(body)
}

// RespMAC is the X-GS-RMac value of an answer.
func RespMAC(macKey []byte, ctr uint64, status int, body []byte) string {
	return MAC(macKey, RespCanonical(ctr, status, body))
}

// VerifyRespMAC checks an X-GS-RMac header in constant time.
func VerifyRespMAC(macKey []byte, ctr uint64, status int, body []byte, rmac string) bool {
	return rmac != "" && VerifyMAC(macKey, RespCanonical(ctr, status, body), rmac)
}

// ---------------------------------------------------------------------------
// §9 Remembered pairing.

// IDSize is the length of the phone id (pid) and receiver id (rid) before encoding.
const IDSize = 16

// ValidID reports whether s is a pid/rid: base64url without padding of 16 bytes.
func ValidID(s string) bool {
	if len(s) != 22 {
		return false
	}
	b, err := B64.Strict().DecodeString(s) // one spelling per id
	return err == nil && len(b) == IDSize
}

// DerivePairKey derives the remembered pair key PK = HKDF(salt = UTF8(sid) || N, IKM = K,
// info = "gs2 pair", 32 bytes). PK must never be sent over the network or logged.
func DerivePairKey(sid string, n, k []byte) ([]byte, error) {
	if len(k) != KeySize {
		return nil, errors.New("gs2: K must be 32 bytes")
	}
	if len(n) != 32 {
		return nil, errors.New("gs2: N must be 32 bytes")
	}
	if !ValidSid(sid) {
		return nil, errors.New("gs2: bad sid")
	}
	salt := make([]byte, 0, len(sid)+len(n))
	salt = append(salt, sid...)
	salt = append(salt, n...)
	return hkdf.Key(sha256.New, k, salt, "gs2 pair", KeySize)
}

// ClaimText is the string covered by a claim proof: "gs2 claim\n" + sid + "\n" + base64url(N).
func ClaimText(sid string, n []byte) string {
	return "gs2 claim\n" + sid + "\n" + B64.EncodeToString(n)
}

// ClaimProof is base64url(HMAC-SHA256(PK, ClaimText(sid, N))).
func ClaimProof(pk []byte, sid string, n []byte) string {
	return MAC(pk, ClaimText(sid, n))
}
