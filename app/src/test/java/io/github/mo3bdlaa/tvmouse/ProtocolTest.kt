package io.github.mo3bdlaa.tvmouse

import io.github.mo3bdlaa.tvmouse.net.Auth
import io.github.mo3bdlaa.tvmouse.net.Crypto
import io.github.mo3bdlaa.tvmouse.net.Pairing
import io.github.mo3bdlaa.tvmouse.net.SecureChannel
import io.github.mo3bdlaa.tvmouse.net.msg
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException

class ProtocolTest {

    @Test fun pairingDerivesTheSameTokenOnBothSides() {
        val phone = Crypto.newKeyPair()
        val tv = Crypto.newKeyPair()
        val phonePk = phone.public.encoded
        val tvPk = tv.public.encoded
        val phoneShared = Crypto.sharedSecret(phone.private, tvPk)
        val tvShared = Crypto.sharedSecret(tv.private, phonePk)
        assertArrayEquals(phoneShared, tvShared)

        val pin = "123456"
        assertTrue(Crypto.same(Pairing.proof(phoneShared, pin, phonePk, tvPk), Pairing.proof(tvShared, pin, phonePk, tvPk)))
        assertFalse(Crypto.same(Pairing.proof(phoneShared, "654321", phonePk, tvPk), Pairing.proof(tvShared, pin, phonePk, tvPk)))
        assertArrayEquals(Pairing.token(phoneShared, phonePk, tvPk), Pairing.token(tvShared, phonePk, tvPk))
    }

    @Test fun authProofsDependOnBothNonces() {
        val token = Crypto.random(32)
        val cn = Crypto.random(16)
        val sn = Crypto.random(16)
        assertFalse(Crypto.same(Auth.phoneProof(token, cn, sn), Auth.tvProof(token, cn, sn)))
        assertFalse(Crypto.same(Auth.phoneProof(token, cn, sn), Auth.phoneProof(token, cn, Crypto.random(16))))
        assertEquals(32, Auth.sessionKey(token, cn, sn).size)
    }

    @Test fun pinsAreSixDigits() {
        repeat(100) { assertTrue(Crypto.newPin().matches(Regex("\\d{6}"))) }
    }

    private fun channelPair(key: ByteArray): Pair<SecureChannel, ByteArrayOutputStream> {
        val wire = ByteArrayOutputStream()
        val sender = SecureChannel(DataInputStream(ByteArrayInputStream(ByteArray(0))), DataOutputStream(wire), key, isPhone = true)
        return sender to wire
    }

    private fun receiver(key: ByteArray, bytes: ByteArray) =
        SecureChannel(DataInputStream(ByteArrayInputStream(bytes)), DataOutputStream(ByteArrayOutputStream()), key, isPhone = false)

    @Test fun secureChannelRoundTripIncludingUnicode() {
        val key = Crypto.random(32)
        val (sender, wire) = channelPair(key)
        sender.send(msg("t" to "text", "s" to "مرحبا hello"))
        sender.send(msg("t" to "move", "x" to -5, "y" to 7))
        val receiver = receiver(key, wire.toByteArray())
        assertEquals("مرحبا hello", receiver.receive().getString("s"))
        assertEquals(-5, receiver.receive().getInt("x"))
    }

    @Test(expected = Exception::class)
    fun secureChannelRejectsTampering() {
        val key = Crypto.random(32)
        val (sender, wire) = channelPair(key)
        sender.send(msg("t" to "text", "s" to "secret"))
        val bytes = wire.toByteArray()
        bytes[bytes.size - 1] = (bytes[bytes.size - 1].toInt() xor 1).toByte()
        receiver(key, bytes).receive()
    }

    @Test(expected = IOException::class)
    fun secureChannelRejectsReplay() {
        val key = Crypto.random(32)
        val (sender, wire) = channelPair(key)
        sender.send(msg("t" to "ping"))
        val frame = wire.toByteArray()
        val receiver = receiver(key, frame + frame)
        receiver.receive()
        receiver.receive()
    }

    @Test(expected = Exception::class)
    fun secureChannelRejectsWrongKey() {
        val (sender, wire) = channelPair(Crypto.random(32))
        sender.send(msg("t" to "ping"))
        receiver(Crypto.random(32), wire.toByteArray()).receive()
    }
}
