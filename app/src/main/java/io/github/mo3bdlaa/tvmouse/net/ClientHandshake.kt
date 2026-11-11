package io.github.mo3bdlaa.tvmouse.net

import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException

/** Phone side of pairing and authentication (see [Proto] for the message flow). */
object ClientHandshake {

    data class Paired(val tvId: String, val tvName: String, val token: ByteArray)

    /** Thrown when the TV answers with an error code such as "not_paired". */
    class TvError(val code: String, message: String) : IOException(message)

    /**
     * Pairs with the TV. [askPin] blocks until the user typed the code (null = give up);
     * it receives a hint after a wrong code. [onChallenge] reports the TV's name first.
     */
    fun pair(
        input: DataInputStream,
        output: DataOutputStream,
        phoneId: String,
        phoneName: String,
        onChallenge: (tvName: String) -> Unit = {},
        askPin: (hint: String?) -> String?,
    ): Paired {
        val keys = Crypto.newKeyPair()
        val phonePk = keys.public.encoded
        Frames.writeJson(
            output,
            msg("t" to "pair", "v" to Proto.VERSION, "id" to phoneId, "name" to phoneName, "pk" to Crypto.b64(phonePk)),
        )
        val challenge = Frames.readJson(input)
        if (challenge.optString("t") != "pair_challenge") throw tvError(challenge)
        val tvPk = Crypto.unb64(challenge.getString("pk"))
        val tvId = challenge.getString("tv")
        val tvName = challenge.getString("name")
        val shared = Crypto.sharedSecret(keys.private, tvPk)
        onChallenge(tvName)

        var hint: String? = null
        while (true) {
            val pin = askPin(hint) ?: throw IOException("Pairing cancelled.")
            Frames.writeJson(output, msg("t" to "pair_proof", "mac" to Crypto.b64(Pairing.proof(shared, pin, phonePk, tvPk))))
            val reply = Frames.readJson(input)
            when (reply.optString("t")) {
                "paired" -> {
                    val expected = Pairing.confirm(shared, pin, phonePk, tvPk)
                    if (!Crypto.same(expected, Crypto.unb64(reply.getString("mac")))) {
                        throw IOException("The TV's answer didn't check out.")
                    }
                    return Paired(tvId, tvName, Pairing.token(shared, phonePk, tvPk))
                }
                "wrong_pin" -> {
                    val left = reply.optInt("left")
                    if (left <= 0) throw IOException("Wrong code too many times. Start pairing again.")
                    hint = "Wrong code. $left attempt(s) left."
                }
                else -> throw tvError(reply)
            }
        }
    }

    /** Proves both sides know the pairing token and returns an encrypted channel. */
    fun authenticate(
        input: DataInputStream,
        output: DataOutputStream,
        phoneId: String,
        tvId: String,
        token: ByteArray,
    ): SecureChannel {
        val cn = Crypto.random(16)
        Frames.writeJson(output, msg("t" to "auth", "v" to Proto.VERSION, "id" to phoneId, "cn" to Crypto.b64(cn)))
        val challenge = Frames.readJson(input)
        if (challenge.optString("t") != "challenge") throw tvError(challenge)
        if (challenge.getString("tv") != tvId) throw IOException("A different TV answered at this address. Pair again.")
        val sn = Crypto.unb64(challenge.getString("sn"))
        Frames.writeJson(output, msg("t" to "auth_proof", "mac" to Crypto.b64(Auth.phoneProof(token, cn, sn))))
        val ok = Frames.readJson(input)
        if (ok.optString("t") != "ok") throw tvError(ok)
        if (!Crypto.same(Crypto.unb64(ok.getString("mac")), Auth.tvProof(token, cn, sn))) {
            throw IOException("The TV couldn't prove it is the paired TV. Pair again.")
        }
        return SecureChannel(input, output, Auth.sessionKey(token, cn, sn), isPhone = true)
    }

    private fun tvError(reply: JSONObject): TvError {
        val code = reply.optString("code", "unexpected")
        val text = when (code) {
            "not_paired" -> "The TV doesn't know this phone (anymore). Pair again."
            "busy" -> "The TV is already pairing with another phone. Try again in a moment."
            "bad_auth" -> "The TV rejected this phone. Forget the TV and pair again."
            else -> "Unexpected answer from the TV."
        }
        return TvError(code, text)
    }
}
