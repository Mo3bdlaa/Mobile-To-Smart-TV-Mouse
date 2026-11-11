package io.github.mo3bdlaa.tvmouse

import io.github.mo3bdlaa.tvmouse.net.ClientHandshake
import io.github.mo3bdlaa.tvmouse.net.msg
import io.github.mo3bdlaa.tvmouse.tv.ReceiverServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Runs the real TV server against the real phone handshake over a local socket. */
class EndToEndTest {

    private val phones = ConcurrentHashMap<String, ByteArray>()
    private val received = LinkedBlockingQueue<JSONObject>()
    private val pins = LinkedBlockingQueue<String>()
    private lateinit var server: ReceiverServer
    private var port = 0

    private val store = object : ReceiverServer.Store {
        override val tvId = "tv-1"
        override val tvName = "Living room TV"
        override fun phoneToken(phoneId: String) = phones[phoneId]
        override fun addPhone(phoneId: String, name: String, token: ByteArray) {
            phones[phoneId] = token
        }
    }

    private val ui = object : ReceiverServer.PairingUi {
        override fun showPin(phoneName: String, pin: String) {
            pins.put(pin)
        }
        override fun hidePin() = Unit
        override fun paired(phoneName: String) = Unit
    }

    @Before fun startServer() {
        server = ReceiverServer(
            store,
            { message, reply ->
                received.put(message)
                if (message.optString("t") == "text") reply("got ${message.getString("s")}")
            },
            ui,
            preferredPort = 0,
        )
        val ready = CountDownLatch(1)
        server.start {
            port = it
            ready.countDown()
        }
        assertTrue(ready.await(5, TimeUnit.SECONDS))
    }

    @After fun stopServer() = server.stop()

    private fun connect(): Triple<Socket, DataInputStream, DataOutputStream> {
        val socket = Socket("127.0.0.1", port).apply { soTimeout = 5_000 }
        return Triple(
            socket,
            DataInputStream(BufferedInputStream(socket.getInputStream())),
            DataOutputStream(BufferedOutputStream(socket.getOutputStream())),
        )
    }

    @Test fun pairThenAuthenticateThenSendInput() {
        val (socket, input, output) = connect()
        val paired = ClientHandshake.pair(input, output, "phone-1", "Pixel") { pins.poll(5, TimeUnit.SECONDS) }
        assertEquals("tv-1", paired.tvId)
        assertEquals("Living room TV", paired.tvName)
        assertNotNull(phones["phone-1"])

        val channel = ClientHandshake.authenticate(input, output, "phone-1", paired.tvId, paired.token)
        channel.send(msg("t" to "move", "x" to 10, "y" to -4))
        channel.send(msg("t" to "text", "s" to "مرحبا"))
        assertEquals(10, received.poll(5, TimeUnit.SECONDS)!!.getInt("x"))
        assertEquals("مرحبا", received.poll(5, TimeUnit.SECONDS)!!.getString("s"))

        // The TV's reply comes back encrypted on the same channel.
        assertEquals("got مرحبا", channel.receive().getString("msg"))
        channel.send(msg("t" to "ping"))
        assertEquals("pong", channel.receive().getString("t"))
        socket.close()

        // A new connection authenticates with the saved token, without pairing again.
        val (socket2, input2, output2) = connect()
        val channel2 = ClientHandshake.authenticate(input2, output2, "phone-1", paired.tvId, paired.token)
        channel2.send(msg("t" to "key", "k" to "home"))
        assertEquals("home", received.poll(5, TimeUnit.SECONDS)!!.getString("k"))
        socket2.close()
    }

    @Test fun wrongPinThenRightPin() {
        val (socket, input, output) = connect()
        var attempts = 0
        val hints = mutableListOf<String?>()
        val paired = ClientHandshake.pair(input, output, "phone-2", "Galaxy") { hint ->
            hints += hint
            attempts++
            val real = pins.peek() ?: pins.poll(5, TimeUnit.SECONDS)
            if (attempts == 1) "000000".takeIf { it != real } ?: "111111" else real
        }
        assertEquals(2, attempts)
        assertEquals(null, hints[0])
        assertTrue(hints[1]!!.contains("Wrong code"))
        assertNotNull(phones["phone-2"])
        assertEquals(paired.token.toList(), phones["phone-2"]!!.toList())
        socket.close()
    }

    @Test(expected = ClientHandshake.TvError::class)
    fun unknownPhoneIsRejected() {
        val (socket, input, output) = connect()
        try {
            ClientHandshake.authenticate(input, output, "stranger", "tv-1", ByteArray(32))
        } finally {
            socket.close()
        }
    }

    @Test(expected = ClientHandshake.TvError::class)
    fun wrongTokenIsRejected() {
        phones["phone-3"] = ByteArray(32) { 1 }
        val (socket, input, output) = connect()
        try {
            ClientHandshake.authenticate(input, output, "phone-3", "tv-1", ByteArray(32) { 2 })
        } finally {
            socket.close()
        }
    }
}
