package io.github.mo3bdlaa.tvmouse.tv

import android.util.Log
import io.github.mo3bdlaa.tvmouse.net.Auth
import io.github.mo3bdlaa.tvmouse.net.Crypto
import io.github.mo3bdlaa.tvmouse.net.Frames
import io.github.mo3bdlaa.tvmouse.net.Pairing
import io.github.mo3bdlaa.tvmouse.net.Proto
import io.github.mo3bdlaa.tvmouse.net.SecureChannel
import io.github.mo3bdlaa.tvmouse.net.msg
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import kotlin.concurrent.thread

/** TV side: accepts phones on the local network, pairs them with a PIN and forwards their input. */
class ReceiverServer(
    private val store: Store,
    private val sink: Sink,
    private val ui: PairingUi,
    private val preferredPort: Int = Proto.DEFAULT_PORT,
) {
    /** Where paired phones are kept and how this TV identifies itself. */
    interface Store {
        val tvId: String
        val tvName: String
        fun phoneToken(phoneId: String): ByteArray?
        fun addPhone(phoneId: String, name: String, token: ByteArray)
    }

    /** Handles one input message. Call [reply] to show a short message on the phone. */
    fun interface Sink {
        fun handle(message: JSONObject, reply: (String) -> Unit)
    }

    interface PairingUi {
        fun showPin(phoneName: String, pin: String)
        fun hidePin()
        fun paired(phoneName: String)
    }

    private var serverSocket: ServerSocket? = null
    private val clients = CopyOnWriteArrayList<Socket>()
    private val pairingLock = Any()
    private var pairing = false

    fun start(onListening: (port: Int) -> Unit) {
        thread(name = "tvmouse-server", isDaemon = true) {
            val server = try {
                bind(preferredPort)
            } catch (e: IOException) {
                bind(0) // default port busy: take any free port (still found via discovery)
            }
            serverSocket = server
            onListening(server.localPort)
            while (!server.isClosed) {
                val socket = try {
                    server.accept()
                } catch (e: IOException) {
                    break
                }
                clients += socket
                thread(name = "tvmouse-client", isDaemon = true) { serve(socket) }
            }
        }
    }

    fun stop() {
        runCatching { serverSocket?.close() }
        clients.forEach { runCatching { it.close() } }
        clients.clear()
    }

    private fun bind(port: Int) = ServerSocket().apply {
        reuseAddress = true
        bind(InetSocketAddress(port))
    }

    private fun serve(socket: Socket) {
        val sender = Executors.newSingleThreadExecutor()
        try {
            socket.tcpNoDelay = true
            socket.soTimeout = 30_000
            val input = DataInputStream(BufferedInputStream(socket.getInputStream()))
            val output = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))

            var first = Frames.readJson(input)
            if (first.optString("t") == "pair") {
                if (!pair(socket, first, input, output)) return
                first = Frames.readJson(input)
            }
            if (first.optString("t") != "auth") return
            val channel = authenticate(first, input, output) ?: return
            socket.soTimeout = 20_000 // the phone pings every 5 seconds

            val reply: (String) -> Unit = { text ->
                sender.execute { runCatching { channel.send(msg("t" to "notice", "msg" to text)) } }
            }
            while (true) {
                val message = channel.receive()
                if (message.optString("t") == "ping") {
                    sender.execute { runCatching { channel.send(msg("t" to "pong")) } }
                    continue
                }
                sink.handle(message, reply)
            }
        } catch (e: Exception) {
            Log.i(TAG, "Phone disconnected: ${e.message}")
        } finally {
            sender.shutdownNow()
            runCatching { socket.close() }
            clients -= socket
        }
    }

    private fun authenticate(hello: JSONObject, input: DataInputStream, output: DataOutputStream): SecureChannel? {
        val token = store.phoneToken(hello.getString("id"))
        if (token == null) {
            Frames.writeJson(output, msg("t" to "error", "code" to "not_paired"))
            return null
        }
        val cn = Crypto.unb64(hello.getString("cn"))
        val sn = Crypto.random(16)
        Frames.writeJson(
            output,
            msg("t" to "challenge", "sn" to Crypto.b64(sn), "tv" to store.tvId, "name" to store.tvName),
        )
        val proof = Frames.readJson(input)
        if (proof.optString("t") != "auth_proof" ||
            !Crypto.same(Crypto.unb64(proof.getString("mac")), Auth.phoneProof(token, cn, sn))
        ) {
            Frames.writeJson(output, msg("t" to "error", "code" to "bad_auth"))
            return null
        }
        Frames.writeJson(output, msg("t" to "ok", "mac" to Crypto.b64(Auth.tvProof(token, cn, sn))))
        return SecureChannel(input, output, Auth.sessionKey(token, cn, sn), isPhone = false)
    }

    private fun pair(socket: Socket, hello: JSONObject, input: DataInputStream, output: DataOutputStream): Boolean {
        synchronized(pairingLock) {
            if (pairing) {
                Frames.writeJson(output, msg("t" to "error", "code" to "busy"))
                return false
            }
            pairing = true
        }
        try {
            val phoneId = hello.getString("id")
            val phoneName = hello.optString("name", "Phone").take(40)
            val phonePk = Crypto.unb64(hello.getString("pk"))
            val keys = Crypto.newKeyPair()
            val tvPk = keys.public.encoded
            val shared = Crypto.sharedSecret(keys.private, phonePk)
            val pin = Crypto.newPin()

            ui.showPin(phoneName, pin)
            Frames.writeJson(
                output,
                msg("t" to "pair_challenge", "pk" to Crypto.b64(tvPk), "tv" to store.tvId, "name" to store.tvName),
            )
            socket.soTimeout = 130_000 // the user is reading the PIN
            for (attempt in 1..MAX_PIN_ATTEMPTS) {
                val proof = Frames.readJson(input)
                if (proof.optString("t") != "pair_proof") return false
                if (Crypto.same(Crypto.unb64(proof.getString("mac")), Pairing.proof(shared, pin, phonePk, tvPk))) {
                    store.addPhone(phoneId, phoneName, Pairing.token(shared, phonePk, tvPk))
                    Frames.writeJson(output, msg("t" to "paired", "mac" to Crypto.b64(Pairing.confirm(shared, pin, phonePk, tvPk))))
                    socket.soTimeout = 30_000
                    ui.paired(phoneName)
                    return true
                }
                Frames.writeJson(output, msg("t" to "wrong_pin", "left" to MAX_PIN_ATTEMPTS - attempt))
            }
            return false
        } finally {
            ui.hidePin()
            synchronized(pairingLock) { pairing = false }
        }
    }

    private companion object {
        const val TAG = "ReceiverServer"
        const val MAX_PIN_ATTEMPTS = 3
    }
}
