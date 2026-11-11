package com.mcarplay.app.carlink

import com.mcarplay.app.video.ExternalVideoFrame
import java.io.Closeable
import java.io.DataInputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Experimental loopback receiver. Bootstrap and OEM certification are deliberately not fabricated. */
class CarlinkReceiver(
    private val pin: String,
    private val event: (String) -> Unit,
    private val video: (ExternalVideoFrame) -> Unit,
    private val audio: (CarlinkAacFrame) -> Unit,
    private val lost: () -> Unit,
    authPort: Int = 57209,
    rtpPort: Int = 15550,
    private val rtspPort: Int = 7236,
) : Closeable {
    private val closed = AtomicBoolean()
    private val resources = CopyOnWriteArrayList<Closeable>()
    private val workers = CopyOnWriteArrayList<Thread>()
    private val owner = AtomicBoolean()
    private val generation = AtomicLong()
    private val sequence = AtomicLong(20)
    private val auth = listen(authPort)
    private val rtp =
        try {
            listen(rtpPort)
        } catch (error: Exception) {
            auth.close()
            throw error
        }

    @Volatile
    private var session: Session? = null
    val localAuthPort: Int get() = auth.localPort
    val localRtpPort: Int get() = rtp.localPort

    private class Session(
        val generation: Long,
        val key: ByteArray,
    ) {
        val resources = CopyOnWriteArrayList<Socket>()
        val configured = CountDownLatch(1)
        val stopped = AtomicBoolean()
        val playing = AtomicBoolean()
        var rtsp: Socket? = null
        var wfd: CarlinkRtspSink? = null
    }

    init {
        worker("accept") {
            emit("LISTENING_LOCAL_ONLY")
            while (!closed.get()) {
                val socket =
                    try {
                        auth.accept()
                    } catch (_: Exception) {
                        break
                    }
                if (!owner.compareAndSet(false, true)) {
                    socket.close()
                    continue
                }
                try {
                    track(socket)
                } catch (_: Exception) {
                    owner.set(false)
                    break
                }
                worker("session") {
                    try {
                        authenticate(socket)
                    } catch (
                        error: Exception,
                    ) {
                        if (!closed.get()) emit("SESSION_FAILED_${error.javaClass.simpleName}")
                    } finally {
                        runCatching { socket.close() }
                        resources.remove(socket)
                        session?.let(::stopSession)
                        session = null
                        owner.set(false)
                    }
                }
            }
        }
        worker("rtp") {
            while (!closed.get()) {
                val socket =
                    try {
                        rtp.accept()
                    } catch (_: Exception) {
                        break
                    }
                val active = session
                if (active == null || active.stopped.get()) {
                    socket.close()
                    continue
                }
                try {
                    track(socket)
                    active.resources += socket
                    socket.soTimeout = 1000
                    val input = DataInputStream(socket.getInputStream())
                    val media =
                        CarlinkMedia(
                            video = { frame -> if (isCurrent(active) && active.playing.get()) video(frame) },
                            audio = { frame -> if (isCurrent(active) && active.playing.get()) audio(frame) },
                            discontinuity = {
                                lost()
                                requestKeyFrame()
                            },
                        )
                    while (isCurrent(active)) {
                        // A timeout after the first length byte invalidates this byte stream.
                        val first =
                            try {
                                input.readUnsignedByte()
                            } catch (_: SocketTimeoutException) {
                                continue
                            }
                        val size = (first shl 8) or input.readUnsignedByte()
                        require(size in 12..65535) { "carlink_rtp_length" }
                        val packet = ByteArray(size)
                        input.readFully(packet)
                        media.rtp(packet)
                    }
                } catch (error: Exception) {
                    if (isCurrent(active)) {
                        emit("MEDIA_ENDED_${error.javaClass.simpleName}")
                        stopSession(active)
                    }
                } finally {
                    runCatching { socket.close() }
                    resources.remove(socket)
                    active.resources.remove(socket)
                }
            }
        }
    }

    private fun authenticate(socket: Socket) {
        socket.soTimeout = 5000
        emit("TCP_ACCEPTED_PEER_APP_UNVERIFIED")
        val result =
            CarlinkPairing(pin).use { pairing ->
                var request = CarlinkPacket.read(socket.getInputStream())
                var accepted = false
                repeat(3) {
                    if (!accepted) {
                        val reply = pairing.respond(request, System.currentTimeMillis() / 1000)
                        socket.getOutputStream().write(reply.encode())
                        accepted = CarlinkFields.decode(reply.payload).number(7) == 0L
                        if (!accepted) request = CarlinkPacket.read(socket.getInputStream())
                    }
                }
                check(accepted) { "carlink_pairing_retry_limit" }
                pairing.confirm(CarlinkPacket.read(socket.getInputStream()), setOf("127.0.0.1"))
            }
        val active = Session(generation.incrementAndGet(), result.second)
        session = active
        emit("AUTH_CONFIRMED_PEER_APP_UNVERIFIED")
        socket.close()
        try {
            val command = dial(active, result.first.address, result.first.controlPort)
            val decrypt = CarlinkCipher(active.key)
            val configId = sequence.incrementAndGet() and 0xffffffffL
            worker("control") {
                try {
                    while (isCurrent(active)) {
                        val packet = CarlinkPacket.read(command.getInputStream())
                        require(packet.source == 1) { "carlink_direction" }
                        val plain = decrypt.open(packet.payload)
                        if (packet.category == 1 && packet.format == 1) {
                            when (packet.method) {
                                1 -> send(active, command, 1, 1, 0, sequence.incrementAndGet(), plain)
                                24 ->
                                    if (packet.kind == 1) {
                                        send(active, command, 1, 25, 2, packet.sequence, display())
                                        active.configured.countDown()
                                    }
                                25 -> if (packet.kind == 2 && packet.sequence == configId) active.configured.countDown()
                                23 -> stopSession(active)
                            }
                        }
                        if (packet.kind == 3) send(active, command, 7, 0, 2, packet.sequence, byteArrayOf(), format = 0)
                    }
                } catch (error: Exception) {
                    if (isCurrent(active)) {
                        emit("CONTROL_ENDED_${error.javaClass.simpleName}")
                        stopSession(active)
                    }
                }
            }
            send(active, command, 1, 24, 1, configId, display())
            check(active.configured.await(5, TimeUnit.SECONDS) && isCurrent(active)) { "carlink_control_unconfirmed" }
            emit("CONTROL_CONFIGURED")
            // A signed OEM certificate is not available. No synthetic certificate or identity is sent.
            // Try only the standard WFD channel: a peer may require CERT and reject or time out here.
            emit("CERTIFICATE_NOT_IMPLEMENTED_WFD_INTEROP_ATTEMPT")
            val screen = dial(active, result.first.address, rtspPort)
            screen.soTimeout = 30000
            active.rtsp = screen
            val wfd = CarlinkRtspSink(rtp.localPort)
            active.wfd = wfd
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
            emit("RTSP_CONNECTED")
            while (isCurrent(active)) {
                if (!active.playing.get()) check(System.nanoTime() < deadline) { "carlink_wfd_timeout" }
                val message = CarlinkRtsp.read(screen.getInputStream())
                val messages = synchronized(wfd) { wfd.receive(message) }
                synchronized(screen) { messages.forEach { screen.getOutputStream().write(it.encode()) } }
                if (wfd.playing && active.playing.compareAndSet(false, true)) {
                    screen.soTimeout = 90000
                    emit("WFD_PLAY_CONFIRMED")
                }
                if (!wfd.playing && active.playing.get()) stopSession(active)
            }
        } finally {
            stopSession(active)
        }
    }

    fun requestKeyFrame(): Boolean {
        val active = session ?: return false
        val wfd = active.wfd ?: return false
        val socket = active.rtsp ?: return false
        val request = synchronized(wfd) { wfd.requestKeyFrame() } ?: return false
        return runCatching {
            synchronized(socket) { socket.getOutputStream().write(request.encode()) }
            true
        }.getOrDefault(false)
    }

    private fun send(
        active: Session,
        socket: Socket,
        category: Int,
        method: Int,
        kind: Int,
        id: Long,
        payload: ByteArray,
        format: Int = 1,
    ) {
        check(isCurrent(active))
        val packet =
            CarlinkPacket(
                id and 0xffffffffL,
                System.currentTimeMillis() / 1000,
                0,
                format,
                kind,
                category,
                method,
                CarlinkCipher(active.key).seal(payload),
            )
        synchronized(socket) { socket.getOutputStream().write(packet.encode()) }
    }

    private fun display(): ByteArray =
        CarlinkFields()
            .put(2, 1280)
            .put(3, 720)
            .put(4, 160)
            .put(5, 1280)
            .put(6, 720)
            .put(7, 30)
            .put(8, 0)
            .put(9, 0)
            .put(10, 0)
            .put(12, 0)
            .put(13, 0)
            .put(14, 1)
            .put(15, 0)
            .put(17, "mcarplay-carlink-experimental".toByteArray())
            .encode()

    private fun isCurrent(active: Session): Boolean = !closed.get() && !active.stopped.get() && session === active

    private fun stopSession(active: Session) {
        if (!active.stopped.compareAndSet(false, true)) return
        active.configured.countDown()
        active.resources.forEach { runCatching { it.close() } }
        resources.removeAll(active.resources.toSet())
        active.key.fill(0)
        if (!closed.get()) {
            emit("SOURCE_DISCONNECTED")
            lost()
        }
    }

    private fun dial(
        active: Session,
        host: String,
        port: Int,
    ): Socket {
        check(isCurrent(active))
        val socket = Socket()
        track(socket)
        active.resources += socket
        check(isCurrent(active))
        socket.connect(InetSocketAddress(host, port), 3000)
        socket.soTimeout = 10000
        socket.tcpNoDelay = true
        return socket
    }

    private fun listen(port: Int): ServerSocket =
        ServerSocket().also {
            try {
                it.reuseAddress = true
                it.bind(InetSocketAddress("127.0.0.1", port), 1)
                track(it)
            } catch (error: Exception) {
                it.close()
                throw error
            }
        }

    private fun track(resource: Closeable) {
        synchronized(resources) {
            if (closed.get()) {
                resource.close()
                error("carlink_stopped")
            }
            resources += resource
        }
    }

    private fun worker(
        name: String,
        block: () -> Unit,
    ) {
        synchronized(workers) {
            if (closed.get()) return
            Thread({
                try {
                    block()
                } finally {
                    workers.remove(Thread.currentThread())
                }
            }, "carlink-$name").apply {
                isDaemon = true
                workers += this
                start()
            }
        }
    }

    private fun emit(message: String) {
        if (!closed.get()) event(message)
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        session?.let(::stopSession)
        synchronized(resources) {
            resources.forEach { runCatching { it.close() } }
            resources.clear()
        }
        workers.forEach { it.interrupt() }
        workers.filter { it !== Thread.currentThread() }.forEach { it.join(1500) }
    }
}
