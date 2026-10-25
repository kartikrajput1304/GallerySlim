package com.galleryslim.share

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Protocol gs2 §9 ("Your computers"): pair key, claim proof, claim/forget, remembered store, phone id. */
class PairingTest {
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    // Vector computed independently (Python hmac/hashlib, RFC 5869 HKDF) straight from docs/protocol-gs2.md §9.
    private val sid = "AAECAwQFBgcICQoL"                       // base64url(00..0b)
    private val n = ByteArray(32) { (0x20 + it).toByte() }     // 20..3f
    private val k = ByteArray(32) { (0x40 + it).toByte() }     // 40..5f
    private val pkHex = "c2b7915ac897103cccab3d7101dda139f50698e75f827101ff2423d051291289"
    private val proofB64 = "_HbgcjY2awOv3fIJ2CdJG1pqYmCospaNRA0FB-CvDFk"
    private val macHex = "d17ef7a62ced78bf2ad3bc92997182f54958913760921ba41d3e0cd67e4c5e2f"
    private val encHex = "5626930acf4619a2f5985b2fcfc9b5c045db79b7d2a96b4ef60a86294c55a0fa"

    private val rid = Gs2.b64url(ByteArray(16) { (0x70 + it).toByte() })
    private val peer = "192.168.1.19"

    @Test fun pairKeyProofAndSessionKeysMatchTheVector() {
        assertEquals(sid, Gs2.b64url(ByteArray(12) { it.toByte() }))
        val pk = Gs2.pairKey(sid, n, k)
        assertEquals(pkHex, hex(pk))
        assertEquals(proofB64, Gs2.b64url(Gs2.claimProof(pk, sid, n)))
        val keys = Gs2.keys(sid, n, pk)
        assertEquals(macHex, hex(keys.mac))
        assertEquals(encHex, hex(keys.enc))
        // The pair key is not the QR session's mac/enc key.
        assertNotEquals(pkHex, hex(Gs2.keys(sid, n, k).mac))
    }

    @Test fun deviceIdFormat() {
        assertTrue(Gs2.isDeviceId(rid))
        assertFalse(Gs2.isDeviceId(null))
        assertFalse(Gs2.isDeviceId(rid.dropLast(1)))
        assertFalse(Gs2.isDeviceId(rid + "A"))
        assertFalse(Gs2.isDeviceId("AAAAAAAAAAAAAAAAAAAA/+"))
        assertFalse(Gs2.isDeviceId("../../../../etc/passwd!"))
    }

    /** QR pairing with a rid → the phone remembers the receiver with PK; returns the store. */
    private fun paired(sessions: ReceiverSessions, store: PairKeys.InMemory) {
        val s = sessions.create(peer, "Kartik's PC", "windows", "Photos", rid)
        assertFalse(sessions.known(s))
        assertNotNull(sessions.approve("gs2:${s.sid}:${Gs2.b64url(k)}", emptyList()))
        assertArrayEquals(Gs2.pairKey(s.sid, s.n, k), store.key(rid))
        assertEquals("Kartik's PC", store.name(rid))
    }

    @Test fun qrApprovalWithoutRidRemembersNothing() {
        val store = PairKeys.InMemory()
        val sessions = ReceiverSessions(pairs = store)
        val s = sessions.create(peer, "PC", "linux", "", null)
        assertNotNull(sessions.approve("gs2:${s.sid}:${Gs2.b64url(k)}", emptyList()))
        assertTrue(store.keys.isEmpty())
        // A malformed rid is ignored, not stored.
        val bad = sessions.create(peer, "PC", "linux", "", "not-a-rid")
        assertNull(bad.rid)
    }

    @Test fun claimAcceptedThenApprovedWithPairKeyDerivedKeys() {
        val store = PairKeys.InMemory()
        val sessions = ReceiverSessions(pairs = store)
        paired(sessions, store)
        val s = sessions.create(peer, "Kartik's PC", "windows", "Photos", rid)
        assertTrue(sessions.known(s))
        assertTrue(sessions.ready().isEmpty())
        // Not ready (and not approvable) before the claim.
        assertNull(sessions.approveKnown(rid, emptyList()))
        val proof = Gs2.b64url(Gs2.claimProof(store.key(rid)!!, s.sid, s.n))
        assertEquals(ReceiverSessions.Claim.Ok, sessions.claim(s.sid, rid, proof, peer))
        assertEquals(s.sid, sessions.ready()[rid]?.sid)
        assertEquals("waiting", sessions.state(s.sid, peer))
        val approved = sessions.approveKnown(rid, emptyList())
        assertNotNull(approved)
        val expected = Gs2.keys(s.sid, s.n, store.key(rid)!!)
        assertArrayEquals(expected.mac, approved!!.keys!!.mac)
        assertArrayEquals(expected.enc, approved.keys!!.enc)
        assertEquals("approved", sessions.state(s.sid, peer))
        assertTrue(sessions.ready().isEmpty())
        // Approving twice is not possible.
        assertNull(sessions.approveKnown(rid, emptyList()))
    }

    @Test fun claimRejected() {
        val store = PairKeys.InMemory()
        val sessions = ReceiverSessions(pairs = store)
        paired(sessions, store)
        val s = sessions.create(peer, "Kartik's PC", "windows", "Photos", rid)
        val good = Gs2.b64url(Gs2.claimProof(store.key(rid)!!, s.sid, s.n))
        // Wrong address: the session is pinned.
        assertEquals(ReceiverSessions.Claim.WrongPeer, sessions.claim(s.sid, rid, good, "192.168.1.66"))
        // Unknown session.
        assertEquals(ReceiverSessions.Claim.Expired, sessions.claim("AAAAAAAAAAAAAAAA", rid, good, peer))
        // A proof for another session's N.
        val other = sessions.create("192.168.1.20", "x", "linux", "", rid)
        assertEquals(ReceiverSessions.Claim.Denied, sessions.claim(other.sid, rid, good, "192.168.1.20"))
        // Proof from a different key.
        val wrongKey = Gs2.b64url(Gs2.claimProof(ByteArray(32), s.sid, s.n))
        assertEquals(ReceiverSessions.Claim.Denied, sessions.claim(s.sid, rid, wrongKey, peer))
        // A rid other than the one the session was created with.
        val otherRid = Gs2.b64url(ByteArray(16) { 1 })
        store.remember(otherRid, "Other", ByteArray(32) { 2 })
        val forOther = Gs2.b64url(Gs2.claimProof(ByteArray(32) { 2 }, s.sid, s.n))
        assertEquals(ReceiverSessions.Claim.Denied, sessions.claim(s.sid, otherRid, forOther, peer))
        // Garbage proof, and a wrong proof once more (third failure).
        assertEquals(ReceiverSessions.Claim.Denied, sessions.claim(s.sid, rid, "%%%", peer))
        assertEquals(ReceiverSessions.Claim.Denied, sessions.claim(s.sid, rid, wrongKey, peer))
        assertTrue(sessions.ready().isEmpty())
        // After MAX_CLAIM_ATTEMPTS failures even the right proof is refused (new session needed).
        assertEquals(ReceiverSessions.Claim.Denied, sessions.claim(s.sid, rid, good, peer))
        val fresh = sessions.create(peer, "Kartik's PC", "windows", "Photos", rid)
        val freshProof = Gs2.b64url(Gs2.claimProof(store.key(rid)!!, fresh.sid, fresh.n))
        assertEquals(ReceiverSessions.Claim.Ok, sessions.claim(fresh.sid, rid, freshProof, peer))
    }

    @Test fun claimOfUnknownOrForgottenReceiverIsRejected() {
        val store = PairKeys.InMemory()
        val sessions = ReceiverSessions(pairs = store)
        // Unknown rid: nothing remembered.
        val u = sessions.create(peer, "PC", "windows", "", rid)
        assertFalse(sessions.known(u))
        assertEquals(ReceiverSessions.Claim.Denied, sessions.claim(u.sid, rid, Gs2.b64url(Gs2.claimProof(ByteArray(32), u.sid, u.n)), peer))

        paired(sessions, store)
        val pk = store.key(rid)!!
        val s = sessions.create(peer, "PC", "windows", "", rid)
        assertEquals(ReceiverSessions.Claim.Ok, sessions.claim(s.sid, rid, Gs2.b64url(Gs2.claimProof(pk, s.sid, s.n)), peer))
        // Forgotten after the claim: no longer ready, can't be approved.
        store.forget(rid)
        assertTrue(sessions.ready().isEmpty())
        assertNull(sessions.approveKnown(rid, emptyList()))
        // Forgotten before the claim: 401.
        val t = sessions.create(peer, "PC", "windows", "", rid)
        assertFalse(sessions.known(t))
        assertEquals(ReceiverSessions.Claim.Denied, sessions.claim(t.sid, rid, Gs2.b64url(Gs2.claimProof(pk, t.sid, t.n)), peer))
    }

    private fun tempDir(): File = Files.createTempDirectory("gs-pair").toFile().apply { deleteOnExit() }

    @Test fun storePersistsAndForgets() {
        val f = File(tempDir(), "paired")
        var clock = 1_000L
        val a = PairedReceiverStore(f) { clock }
        val pk = ByteArray(32) { (it * 3).toByte() }
        a.remember(rid, "Kartik's PC‮", pk)
        assertEquals(listOf(PairedReceiver(rid, "Kartik's PC", 1_000L, 1_000L)), a.list.value)
        clock = 200_000L
        a.seen(rid)
        val b = PairedReceiverStore(f) { clock }
        assertArrayEquals(pk, b.key(rid))
        assertEquals("Kartik's PC", b.name(rid))
        assertEquals(200_000L, b.list.value.single().lastSeen)
        b.forget(rid)
        assertNull(b.key(rid))
        assertTrue(PairedReceiverStore(f).list.value.isEmpty())
        // A damaged file is ignored, not fatal.
        f.writeText("garbage\n\t\t\t\t\n")
        assertTrue(PairedReceiverStore(f).list.value.isEmpty())
    }

    @Test fun phoneIdIsStable() {
        val f = File(tempDir(), "pid")
        val first = PhoneId.load(f)
        assertTrue(Gs2.isDeviceId(first))
        assertEquals(first, PhoneId.load(f))
        assertEquals(first, PhoneId.load(f))
        // Another install (another file) gets another id.
        assertNotEquals(first, PhoneId.load(File(tempDir(), "pid")))
        // A malformed file is replaced once, then stable again.
        f.writeText("nope")
        val second = PhoneId.load(f)
        assertTrue(Gs2.isDeviceId(second))
        assertEquals(second, PhoneId.load(f))
    }
}
