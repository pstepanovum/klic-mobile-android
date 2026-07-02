package com.klic.mobile.app.data

import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.SessionBuilder
import org.signal.libsignal.protocol.SessionCipher
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.ecc.ECKeyPair
import org.signal.libsignal.protocol.kem.KEMKeyPair
import org.signal.libsignal.protocol.kem.KEMKeyType
import org.signal.libsignal.protocol.message.CiphertextMessage
import org.signal.libsignal.protocol.message.PreKeySignalMessage
import org.signal.libsignal.protocol.message.SignalMessage
import org.signal.libsignal.protocol.state.KyberPreKeyRecord
import org.signal.libsignal.protocol.state.PreKeyBundle
import org.signal.libsignal.protocol.state.PreKeyRecord
import org.signal.libsignal.protocol.state.SignedPreKeyRecord

/**
 * End-to-end Signal-protocol round trip through our persistent store and the
 * Content codec — real libsignal crypto (the client jar bundles desktop natives).
 */
class E2eeRoundTripTest {

    private class Party(val name: String) {
        val identity: IdentityKeyPair = IdentityKeyPair.generate()
        val snapshot = AtomicReference(E2eeStoreSnapshot())
        val store = KlicSignalStore(identity, 1000 + name.hashCode().mod(1000), snapshot.get()) {
            snapshot.set(it)
        }
        val address = SignalProtocolAddress(name, 1)

        /** Rebuild the store from what was persisted — simulating an app restart. */
        fun restarted() = KlicSignalStore(identity, 1, snapshot.get()) { snapshot.set(it) }
    }

    private fun publishBundle(party: Party): PreKeyBundle {
        val now = System.currentTimeMillis()
        val preKey = PreKeyRecord(1, ECKeyPair.generate())
        val spkPair = ECKeyPair.generate()
        val spk = SignedPreKeyRecord(
            1, now, spkPair, party.identity.privateKey.calculateSignature(spkPair.publicKey.serialize()))
        val kyberPair = KEMKeyPair.generate(KEMKeyType.KYBER_1024)
        val kyber = KyberPreKeyRecord(
            1, now, kyberPair, party.identity.privateKey.calculateSignature(kyberPair.publicKey.serialize()))

        party.store.storePreKey(1, preKey)
        party.store.storeSignedPreKey(1, spk)
        party.store.storeKyberPreKey(1, kyber)

        return PreKeyBundle(
            party.store.localRegistrationId,
            1,
            1,
            preKey.keyPair.publicKey,
            1,
            spkPair.publicKey,
            spk.signature,
            party.identity.publicKey,
            1,
            kyberPair.publicKey,
            kyber.signature,
        )
    }

    @Test
    fun fullRoundTripWithPersistence() {
        val alice = Party("alice")
        val bob = Party("bob")

        // Alice fetches Bob's bundle and establishes a session.
        SessionBuilder(alice.store, bob.address).process(publishBundle(bob))

        // Alice -> Bob: first message rides a PreKey message.
        val hello = SessionCipher(alice.store, bob.address)
            .encrypt(E2eeCodec.encode(E2eeContent.text("hello bob")))
        assertEquals(CiphertextMessage.PREKEY_TYPE, hello.type)

        val helloPlain = SessionCipher(bob.store, alice.address)
            .decrypt(PreKeySignalMessage(hello.serialize()))
        assertEquals("hello bob", E2eeCodec.decode(helloPlain)?.text)

        // Bob's one-time prekeys were consumed by processing the handshake.
        assertFalse("EC one-time prekey must be consumed", bob.store.containsPreKey(1))
        assertFalse("one-time kyber prekey must be consumed", bob.store.containsKyberPreKey(1))

        // Bob -> Alice: reply flows over the established session (Whisper).
        val reply = SessionCipher(bob.store, alice.address)
            .encrypt(E2eeCodec.encode(E2eeContent.text("hi alice")))
        assertEquals(CiphertextMessage.WHISPER_TYPE, reply.type)
        val replyPlain = SessionCipher(alice.store, bob.address).decrypt(SignalMessage(reply.serialize()))
        assertEquals("hi alice", E2eeCodec.decode(replyPlain)?.text)

        // Restart both sides from their persisted snapshots — sessions must survive.
        val aliceAfterRestart = alice.restarted()
        val bobAfterRestart = bob.restarted()
        val late = SessionCipher(bobAfterRestart, alice.address)
            .encrypt(E2eeCodec.encode(E2eeContent(type = "reaction", emoji = "🔥", targetMessageId = "m1")))
        val latePlain = SessionCipher(aliceAfterRestart, bob.address).decrypt(SignalMessage(late.serialize()))
        val content = E2eeCodec.decode(latePlain)
        assertEquals("reaction", content?.type)
        assertEquals("🔥", content?.emoji)
        assertEquals("m1", content?.targetMessageId)

        // Identities were recorded (trust-on-first-use) for the Phase 6 safety-number UI.
        assertTrue(alice.store.getIdentity(bob.address) != null)
        assertTrue(bob.store.getIdentity(alice.address) != null)
    }

    @Test
    fun codecToleratesUnknownFieldsAndRejectsGarbage() {
        assertNull(E2eeCodec.decode(byteArrayOf(0x1, 0x2, 0x3)))
        val future = """{"v":9,"type":"hologram","shimmer":true}""".encodeToByteArray()
        assertEquals("hologram", E2eeCodec.decode(future)?.type) // renders as "update Klic"
        val roundTrip = E2eeCodec.decode(E2eeCodec.encode(E2eeContent.text("привет", E2eeQuote("m9", "yo", "TEXT"))))
        assertEquals("привет", roundTrip?.text)
        assertEquals("m9", roundTrip?.quote?.messageId)
    }
}
