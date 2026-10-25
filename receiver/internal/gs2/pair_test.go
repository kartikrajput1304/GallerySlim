package gs2

import (
	"encoding/hex"
	"encoding/json"
	"os"
	"testing"
)

// The §9 vectors in testdata/gs2-pair-vectors.json were computed independently (Python hmac /
// hashlib HKDF), so they check this package rather than repeat it.
func TestPairVectors(t *testing.T) {
	raw, err := os.ReadFile("../../testdata/gs2-pair-vectors.json")
	if err != nil {
		t.Fatal(err)
	}
	var v map[string]string
	if err := json.Unmarshal(raw, &v); err != nil {
		t.Fatal(err)
	}
	k, _ := B64.DecodeString(v["K_b64url"])
	n, _ := B64.DecodeString(v["N_b64url"])
	sid := v["sid"]
	pk, err := DerivePairKey(sid, n, k)
	if err != nil {
		t.Fatal(err)
	}
	if hex.EncodeToString(pk) != v["pairKey_hex"] {
		t.Errorf("PK %x", pk)
	}
	if ClaimText(sid, n) != v["claim_text"] {
		t.Errorf("claim text %q", ClaimText(sid, n))
	}
	if ClaimProof(pk, sid, n) != v["claim_proof_b64url"] {
		t.Errorf("proof %s", ClaimProof(pk, sid, n))
	}
	keys, err := DeriveKeys(sid, n, pk) // session keys of a known session: IKM = PK
	if err != nil {
		t.Fatal(err)
	}
	if hex.EncodeToString(keys.Mac[:]) != v["known_macKey_hex"] || hex.EncodeToString(keys.Enc[:]) != v["known_encKey_hex"] {
		t.Error("known-session keys differ")
	}
	if !ValidID(v["rid"]) || !ValidID(v["pid"]) {
		t.Error("vector ids rejected")
	}
	for _, bad := range []string{"", "short", "ICEiIyQlJicoKSorLC0uL", "ICEiIyQlJicoKSorLC0uLw=", "ICEiIyQlJicoKSorLC0u+w", "ICEiIyQlJicoKSorLC0uL\n"} {
		if ValidID(bad) {
			t.Errorf("ValidID(%q)", bad)
		}
	}
	if _, err := DerivePairKey(sid, n, k[:31]); err == nil {
		t.Error("short K accepted")
	}
	if _, err := DerivePairKey("bad", n, k); err == nil {
		t.Error("bad sid accepted")
	}
}

// The vector the phone side (Kotlin) tests against, from docs/protocol-gs2.md §9:
// sid = base64url(00..0b), N = 20..3f, K = 40..5f.
func TestPairVectorPhoneSide(t *testing.T) {
	seq := func(from, n int) []byte {
		b := make([]byte, n)
		for i := range b {
			b[i] = byte(from + i)
		}
		return b
	}
	sid := B64.EncodeToString(seq(0, 12))
	if sid != "AAECAwQFBgcICQoL" {
		t.Fatalf("sid %s", sid)
	}
	n, k := seq(0x20, 32), seq(0x40, 32)
	pk, err := DerivePairKey(sid, n, k)
	if err != nil {
		t.Fatal(err)
	}
	keys, _ := DeriveKeys(sid, n, pk)
	for name, got := range map[string]string{
		"PK":     hex.EncodeToString(pk),
		"proof":  ClaimProof(pk, sid, n),
		"macKey": hex.EncodeToString(keys.Mac[:]),
		"encKey": hex.EncodeToString(keys.Enc[:]),
	} {
		want := map[string]string{
			"PK":     "c2b7915ac897103cccab3d7101dda139f50698e75f827101ff2423d051291289",
			"proof":  "_HbgcjY2awOv3fIJ2CdJG1pqYmCospaNRA0FB-CvDFk",
			"macKey": "d17ef7a62ced78bf2ad3bc92997182f54958913760921ba41d3e0cd67e4c5e2f",
			"encKey": "5626930acf4619a2f5985b2fcfc9b5c045db79b7d2a96b4ef60a86294c55a0fa",
		}[name]
		if got != want {
			t.Errorf("%s = %s, want %s", name, got, want)
		}
	}
}
