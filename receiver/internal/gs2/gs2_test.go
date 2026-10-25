package gs2

import (
	"bytes"
	"encoding/hex"
	"encoding/json"
	"errors"
	"io"
	"os"
	"strconv"
	"strings"
	"testing"
)

type vectors struct {
	K          string `json:"K_b64url"`
	N          string `json:"N_b64url"`
	AckCanon   string `json:"ack_canonical"`
	AckMAC     string `json:"ack_mac_b64url"`
	EncKey     string `json:"encKey_hex"`
	FrameSize  string `json:"frames_frameSize"`
	FrameOff   string `json:"frames_offset"`
	FramePlain string `json:"frames_plaintext"`
	FramePre   string `json:"frames_prefix_hex"`
	FrameRes   string `json:"frames_resource"`
	FrameHex   string `json:"frames_stream_hex"`
	GetCanon   string `json:"get_canonical"`
	GetMAC     string `json:"get_mac_b64url"`
	MacKey     string `json:"macKey_hex"`
	QR         string `json:"qr"`
	Sid        string `json:"sid"`
	RMac       string `json:"rmac_b64url"`
	RMacBody   string `json:"rmac_body"`
	RMacCtr    string `json:"rmac_ctr"`
	RMacStatus string `json:"rmac_status"`
}

func loadVectors(t *testing.T) vectors {
	t.Helper()
	b, err := os.ReadFile("../../testdata/gs2-vectors.json")
	if err != nil {
		t.Fatal(err)
	}
	var v vectors
	if err := json.Unmarshal(b, &v); err != nil {
		t.Fatal(err)
	}
	return v
}

func mustB64(t *testing.T, s string) []byte {
	t.Helper()
	b, err := B64.DecodeString(s)
	if err != nil {
		t.Fatal(err)
	}
	return b
}

func mustHex(t *testing.T, s string) []byte {
	t.Helper()
	b, err := hex.DecodeString(s)
	if err != nil {
		t.Fatal(err)
	}
	return b
}

func vectorKeys(t *testing.T, v vectors) Keys {
	keys, err := DeriveKeys(v.Sid, mustB64(t, v.N), mustB64(t, v.K))
	if err != nil {
		t.Fatal(err)
	}
	return keys
}

func TestVectorHKDF(t *testing.T) {
	v := loadVectors(t)
	keys := vectorKeys(t, v)
	if got := hex.EncodeToString(keys.Mac[:]); got != v.MacKey {
		t.Errorf("macKey = %s, want %s", got, v.MacKey)
	}
	if got := hex.EncodeToString(keys.Enc[:]); got != v.EncKey {
		t.Errorf("encKey = %s, want %s", got, v.EncKey)
	}
}

func TestVectorGetCanonicalAndMAC(t *testing.T) {
	v := loadVectors(t)
	keys := vectorKeys(t, v)
	c := Canonical("GET", "/api/r/file?f=42", "bytes=3-", v.Sid, 8, nil)
	if c != v.GetCanon {
		t.Fatalf("canonical = %q, want %q", c, v.GetCanon)
	}
	if m := MAC(keys.Mac[:], c); m != v.GetMAC {
		t.Errorf("mac = %s, want %s", m, v.GetMAC)
	}
	if !VerifyMAC(keys.Mac[:], c, v.GetMAC) {
		t.Error("VerifyMAC rejected the vector")
	}
	if VerifyMAC(keys.Mac[:], c+"x", v.GetMAC) {
		t.Error("VerifyMAC accepted a changed canonical string")
	}
}

func TestVectorAckCanonicalAndMAC(t *testing.T) {
	v := loadVectors(t)
	keys := vectorKeys(t, v)
	// Rebuild the canonical string from its fields; the body itself is not in the vectors, only its hash.
	parts := strings.Split(v.AckCanon, "\n")
	if len(parts) != 6 {
		t.Fatalf("ack canonical has %d lines", len(parts))
	}
	ctr, err := strconv.ParseUint(parts[4], 10, 64)
	if err != nil {
		t.Fatal(err)
	}
	rebuilt := Canonical(parts[0], parts[1], parts[2], parts[3], ctr, nil)
	rebuilt = rebuilt[:strings.LastIndexByte(rebuilt, '\n')+1] + parts[5]
	if rebuilt != v.AckCanon || parts[0] != "POST" || parts[1] != "/api/r/ack" || parts[2] != "" || parts[3] != v.Sid {
		t.Fatalf("ack canonical layout mismatch: %q", rebuilt)
	}
	if m := MAC(keys.Mac[:], v.AckCanon); m != v.AckMAC {
		t.Errorf("ack mac = %s, want %s", m, v.AckMAC)
	}
}

func TestVectorSignedAnswer(t *testing.T) {
	v := loadVectors(t)
	keys := vectorKeys(t, v)
	ctr, err := strconv.ParseUint(v.RMacCtr, 10, 64)
	if err != nil {
		t.Fatal(err)
	}
	status, err := strconv.Atoi(v.RMacStatus)
	if err != nil {
		t.Fatal(err)
	}
	body := []byte(v.RMacBody)
	if got := RespMAC(keys.Mac[:], ctr, status, body); got != v.RMac {
		t.Fatalf("rmac = %s, want %s", got, v.RMac)
	}
	if !VerifyRespMAC(keys.Mac[:], ctr, status, body, v.RMac) {
		t.Fatal("vector rejected")
	}
	flipped := []byte(strings.Replace(v.RMacBody, "true}", "false}", 1))
	for _, bad := range []struct {
		ctr    uint64
		status int
		body   []byte
		mac    string
	}{
		{ctr + 1, status, body, v.RMac},
		{ctr, 409, body, v.RMac},
		{ctr, status, flipped, v.RMac},
		{ctr, status, body, ""},
		{ctr, status, body, "not base64!"},
	} {
		if VerifyRespMAC(keys.Mac[:], bad.ctr, bad.status, bad.body, bad.mac) {
			t.Errorf("accepted %+v", bad)
		}
	}
}

func TestEmptyBodyHash(t *testing.T) {
	if got := BodyHashHex(nil); got != "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855" {
		t.Fatal(got)
	}
}

func TestVectorQR(t *testing.T) {
	v := loadVectors(t)
	if got := QRText(v.Sid, mustB64(t, v.K)); got != v.QR {
		t.Fatalf("qr = %q, want %q", got, v.QR)
	}
	sid, k, err := ParseQRText(v.QR)
	if err != nil || sid != v.Sid || !bytes.Equal(k, mustB64(t, v.K)) {
		t.Fatalf("ParseQRText: %v %q", err, sid)
	}
}

func vectorFrameParams(t *testing.T, v vectors) (frameSize int, off uint64) {
	fs, err := strconv.Atoi(v.FrameSize)
	if err != nil {
		t.Fatal(err)
	}
	o, err := strconv.ParseUint(v.FrameOff, 10, 64)
	if err != nil {
		t.Fatal(err)
	}
	return fs, o
}

func decodeAll(stream []byte, key []byte, sid, res string, off uint64, fs int) ([]byte, error) {
	d, err := NewDecoder(bytes.NewReader(stream), key, sid, res, off, fs)
	if err != nil {
		return nil, err
	}
	var out []byte
	want := off
	for {
		p, o, err := d.Next()
		if err == io.EOF {
			return out, nil
		}
		if err != nil {
			return out, err
		}
		if o != want {
			return out, errors.New("offset mismatch")
		}
		want += uint64(len(p))
		out = append(out, p...)
	}
}

func TestVectorFrameDecode(t *testing.T) {
	v := loadVectors(t)
	keys := vectorKeys(t, v)
	fs, off := vectorFrameParams(t, v)
	got, err := decodeAll(mustHex(t, v.FrameHex), keys.Enc[:], v.Sid, v.FrameRes, off, fs)
	if err != nil {
		t.Fatal(err)
	}
	if string(got) != v.FramePlain {
		t.Fatalf("plaintext = %q", got)
	}
}

func TestVectorFrameEncode(t *testing.T) {
	v := loadVectors(t)
	keys := vectorKeys(t, v)
	fs, off := vectorFrameParams(t, v)
	var buf bytes.Buffer
	e, err := NewEncoder(&buf, keys.Enc[:], mustHex(t, v.FramePre), v.Sid, v.FrameRes, off, fs)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := e.Write([]byte(v.FramePlain)); err != nil {
		t.Fatal(err)
	}
	if err := e.Close(); err != nil {
		t.Fatal(err)
	}
	if got := hex.EncodeToString(buf.Bytes()); got != v.FrameHex {
		t.Fatalf("stream = %s\nwant     %s", got, v.FrameHex)
	}
}

// encode is a test helper building a stream with the given options.
func encode(t *testing.T, key []byte, sid, res string, off uint64, fs int, data []byte, closeLast bool) []byte {
	t.Helper()
	var buf bytes.Buffer
	e, err := NewEncoder(&buf, key, []byte{9, 8, 7, 6, 5, 4, 3, 2}, sid, res, off, fs)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := e.Write(data); err != nil {
		t.Fatal(err)
	}
	if closeLast {
		if err := e.Close(); err != nil {
			t.Fatal(err)
		}
	} else if err := e.FlushNotLast(); err != nil {
		t.Fatal(err)
	}
	return buf.Bytes()
}

func TestDecoderStrictness(t *testing.T) {
	v := loadVectors(t)
	keys := vectorKeys(t, v)
	key := keys.Enc[:]
	sid := v.Sid
	fs := 8
	data := []byte("0123456789abcdefXYZ") // 19 bytes: frames of 8, 8, 3
	good := encode(t, key, sid, "f:1", 0, fs, data, true)

	if got, err := decodeAll(good, key, sid, "f:1", 0, fs); err != nil || !bytes.Equal(got, data) {
		t.Fatalf("good stream: %v %q", err, got)
	}

	t.Run("every single-bit flip is rejected", func(t *testing.T) {
		for i := 0; i < len(good); i++ {
			bad := bytes.Clone(good)
			bad[i] ^= 0x01
			got, err := decodeAll(bad, key, sid, "f:1", 0, fs)
			if err == nil {
				t.Fatalf("flip at %d accepted", i)
			}
			// Whatever was returned before the error must be an authentic prefix.
			if !bytes.HasPrefix(data, got) {
				t.Fatalf("flip at %d leaked unauthenticated bytes %q", i, got)
			}
		}
	})

	t.Run("every truncation is rejected", func(t *testing.T) {
		for n := 0; n < len(good); n++ {
			got, err := decodeAll(good[:n], key, sid, "f:1", 0, fs)
			if !errors.Is(err, ErrNoLast) {
				t.Fatalf("truncation to %d: err = %v", n, err)
			}
			if !bytes.HasPrefix(data, got) {
				t.Fatalf("truncation to %d leaked %q", n, got)
			}
		}
	})

	t.Run("wrong resource, sid, offset or key", func(t *testing.T) {
		if _, err := decodeAll(good, key, sid, "f:2", 0, fs); !errors.Is(err, ErrTampered) {
			t.Errorf("resource: %v", err)
		}
		if _, err := decodeAll(good, key, "ZZCdEfGhIjKlMnOp", "f:1", 0, fs); !errors.Is(err, ErrTampered) {
			t.Errorf("sid: %v", err)
		}
		if _, err := decodeAll(good, key, sid, "f:1", 8, fs); !errors.Is(err, ErrTampered) {
			t.Errorf("offset: %v", err)
		}
		other := bytes.Clone(key)
		other[0] ^= 1
		if _, err := decodeAll(good, other, sid, "f:1", 0, fs); !errors.Is(err, ErrTampered) {
			t.Errorf("key: %v", err)
		}
	})

	t.Run("frames swapped", func(t *testing.T) {
		// prefix(8) | len(4) ct(24) | len(4) ct(24) | len(4) ct(19)
		f1 := good[8 : 8+28]
		f2 := good[8+28 : 8+56]
		swapped := append(append(append(bytes.Clone(good[:8]), f2...), f1...), good[8+56:]...)
		if _, err := decodeAll(swapped, key, sid, "f:1", 0, fs); !errors.Is(err, ErrTampered) {
			t.Errorf("swap: %v", err)
		}
	})

	t.Run("last frame dropped", func(t *testing.T) {
		if _, err := decodeAll(good[:8+56], key, sid, "f:1", 0, fs); !errors.Is(err, ErrNoLast) {
			t.Errorf("err = %v", err)
		}
	})

	t.Run("full final frame without LAST", func(t *testing.T) {
		s := encode(t, key, sid, "f:1", 0, fs, data[:16], false)
		got, err := decodeAll(s, key, sid, "f:1", 0, fs)
		if !errors.Is(err, ErrNoLast) || !bytes.Equal(got, data[:16]) {
			t.Errorf("err = %v got %q", err, got)
		}
	})

	t.Run("short final frame without LAST", func(t *testing.T) {
		s := encode(t, key, sid, "f:1", 0, fs, data, false)
		if _, err := decodeAll(s, key, sid, "f:1", 0, fs); !errors.Is(err, ErrTampered) {
			t.Errorf("err = %v", err)
		}
	})

	t.Run("full final frame with LAST", func(t *testing.T) {
		s := encode(t, key, sid, "f:1", 0, fs, data[:16], true)
		got, err := decodeAll(s, key, sid, "f:1", 0, fs)
		if err != nil || !bytes.Equal(got, data[:16]) {
			t.Errorf("err = %v got %q", err, got)
		}
	})

	t.Run("empty resource", func(t *testing.T) {
		s := encode(t, key, sid, "list", 0, fs, nil, true)
		if len(s) != 8+4+16 {
			t.Fatalf("len %d", len(s))
		}
		got, err := decodeAll(s, key, sid, "list", 0, fs)
		if err != nil || len(got) != 0 {
			t.Errorf("err = %v got %q", err, got)
		}
	})

	t.Run("trailing data after LAST", func(t *testing.T) {
		s := append(bytes.Clone(good), 0)
		if _, err := decodeAll(s, key, sid, "f:1", 0, fs); !errors.Is(err, ErrMalformed) {
			t.Errorf("err = %v", err)
		}
	})

	t.Run("oversized and undersized length", func(t *testing.T) {
		s := bytes.Clone(good)
		s[8], s[9], s[10], s[11] = 0, 0, 0, byte(fs+TagSize+1)
		if _, err := decodeAll(s, key, sid, "f:1", 0, fs); !errors.Is(err, ErrMalformed) {
			t.Errorf("big: %v", err)
		}
		s[11] = 15
		if _, err := decodeAll(s, key, sid, "f:1", 0, fs); !errors.Is(err, ErrMalformed) {
			t.Errorf("small: %v", err)
		}
	})

	t.Run("frame size is enforced", func(t *testing.T) {
		big := encode(t, key, sid, "f:1", 0, 16, data, true)
		if _, err := decodeAll(big, key, sid, "f:1", 0, fs); !errors.Is(err, ErrMalformed) {
			t.Errorf("err = %v", err)
		}
	})
}

func TestDeriveKeysValidation(t *testing.T) {
	n := make([]byte, 32)
	if _, err := DeriveKeys("short", n, make([]byte, 32)); err == nil {
		t.Error("short sid accepted")
	}
	if _, err := DeriveKeys("AbCdEfGhIjKlMnOp", n, make([]byte, 31)); err == nil {
		t.Error("short K accepted")
	}
	if _, err := DeriveKeys("AbCdEfGhIjKlMnOp", n[:31], make([]byte, 32)); err == nil {
		t.Error("short N accepted")
	}
}
