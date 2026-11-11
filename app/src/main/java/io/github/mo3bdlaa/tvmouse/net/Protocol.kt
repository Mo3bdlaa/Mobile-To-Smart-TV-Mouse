package io.github.mo3bdlaa.tvmouse.net

import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/*
 * Wi-Fi protocol between the phone (controller) and the TV (receiver).
 *
 * Transport: TCP, each frame = 4-byte big-endian length + payload.
 *
 * Pairing (once per phone/TV pair), plain JSON frames:
 *   phone -> tv   {t:"pair", id, name, pk}          pk = phone EC P-256 public key
 *   tv -> phone   {t:"pair_challenge", pk, tv, name} TV shows a 6-digit PIN on screen
 *   phone -> tv   {t:"pair_proof", mac}             mac = HMAC(ECDH secret, PIN, keys)
 *   tv -> phone   {t:"paired", mac} | {t:"wrong_pin", left}
 *   Both sides derive the same long-term token from the ECDH secret.
 *
 * Every connection then authenticates with the token (challenge-response in both
 * directions) and switches to AES-256-GCM encrypted JSON frames:
 *   phone -> tv   {t:"auth", id, cn}
 *   tv -> phone   {t:"challenge", sn, tv, name} | {t:"error", code}
 *   phone -> tv   {t:"auth_proof", mac}
 *   tv -> phone   {t:"ok", mac}
 */
object Proto {
    const val SERVICE_TYPE = "_tvmouse._tcp"
    const val DEFAULT_PORT = 47321
    const val VERSION = 1
    const val MAX_FRAME = 1 shl 20
}

object Frames {
    fun write(out: DataOutputStream, payload: ByteArray) {
        synchronized(out) {
            out.writeInt(payload.size)
            out.write(payload)
            out.flush()
        }
    }

    fun read(input: DataInputStream): ByteArray {
        val size = input.readInt()
        if (size < 0 || size > Proto.MAX_FRAME) throw IOException("Invalid frame size $size")
        return ByteArray(size).also { input.readFully(it) }
    }

    fun writeJson(out: DataOutputStream, json: JSONObject) = write(out, json.toString().toByteArray(Charsets.UTF_8))

    fun readJson(input: DataInputStream): JSONObject = JSONObject(String(read(input), Charsets.UTF_8))
}

object Crypto {
    private val random = SecureRandom()

    fun random(size: Int): ByteArray = ByteArray(size).also(random::nextBytes)

    fun hmac(key: ByteArray, label: String, vararg parts: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        mac.update(label.toByteArray(Charsets.UTF_8))
        for (part in parts) {
            mac.update(ByteBuffer.allocate(4).putInt(part.size).array())
            mac.update(part)
        }
        return mac.doFinal()
    }

    fun newKeyPair(): KeyPair =
        KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()

    fun sharedSecret(own: PrivateKey, peerPublicKey: ByteArray): ByteArray {
        val peer = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(peerPublicKey))
        return KeyAgreement.getInstance("ECDH").run {
            init(own)
            doPhase(peer, true)
            generateSecret()
        }
    }

    fun same(a: ByteArray, b: ByteArray): Boolean = MessageDigest.isEqual(a, b)

    fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    fun unb64(text: String): ByteArray = Base64.getDecoder().decode(text)

    fun newPin(): String = (100_000 + random.nextInt(900_000)).toString()
}

object Pairing {
    fun proof(shared: ByteArray, pin: String, phonePk: ByteArray, tvPk: ByteArray) =
        Crypto.hmac(shared, "pair-proof", pin.toByteArray(), phonePk, tvPk)

    fun confirm(shared: ByteArray, pin: String, phonePk: ByteArray, tvPk: ByteArray) =
        Crypto.hmac(shared, "pair-confirm", pin.toByteArray(), phonePk, tvPk)

    fun token(shared: ByteArray, phonePk: ByteArray, tvPk: ByteArray) =
        Crypto.hmac(shared, "pair-token", phonePk, tvPk)
}

object Auth {
    fun phoneProof(token: ByteArray, cn: ByteArray, sn: ByteArray) = Crypto.hmac(token, "auth-phone", cn, sn)
    fun tvProof(token: ByteArray, cn: ByteArray, sn: ByteArray) = Crypto.hmac(token, "auth-tv", cn, sn)
    fun sessionKey(token: ByteArray, cn: ByteArray, sn: ByteArray) = Crypto.hmac(token, "session", cn, sn)
}

/** AES-256-GCM encrypted JSON frames. Each direction has its own counter-based nonce. */
class SecureChannel(
    private val input: DataInputStream,
    private val output: DataOutputStream,
    key: ByteArray,
    isPhone: Boolean,
) {
    private val keySpec = SecretKeySpec(key, "AES")
    private val sendDirection: Byte = if (isPhone) 1 else 2
    private val receiveDirection: Byte = if (isPhone) 2 else 1
    private var sendCounter = 0L
    private var receiveCounter = 0L

    @Synchronized
    fun send(message: JSONObject) {
        sendCounter++
        val iv = nonce(sendDirection, sendCounter)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, GCMParameterSpec(128, iv))
        val body = cipher.doFinal(message.toString().toByteArray(Charsets.UTF_8))
        Frames.write(output, iv + body)
    }

    fun receive(): JSONObject {
        val frame = Frames.read(input)
        if (frame.size < 12 + 16) throw IOException("Frame too short")
        val iv = frame.copyOfRange(0, 12)
        if (iv[0] != receiveDirection) throw IOException("Wrong direction")
        val counter = ByteBuffer.wrap(iv, 4, 8).long
        if (counter <= receiveCounter) throw IOException("Replayed frame")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, keySpec, GCMParameterSpec(128, iv))
        val plain = cipher.doFinal(frame, 12, frame.size - 12) // throws if tampered
        receiveCounter = counter
        return JSONObject(String(plain, Charsets.UTF_8))
    }

    private fun nonce(direction: Byte, counter: Long): ByteArray =
        ByteBuffer.allocate(12).put(byteArrayOf(direction, 0, 0, 0)).putLong(counter).array()
}

/** Small helper so call sites read like `msg("t" to "move", "x" to 3)`. */
fun msg(vararg pairs: Pair<String, Any?>): JSONObject = JSONObject().apply {
    for ((key, value) in pairs) put(key, value)
}
