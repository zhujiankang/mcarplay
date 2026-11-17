package com.mcarplay.app.airplay

import com.mcarplay.app.iap2.Iap2SenderSession
import com.mcarplay.app.video.ExternalVideoSource
import com.mcarplay.app.video.VideoSender
import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Independent dual-TCP peer: an unannounced video EOF terminates its owning control session. */
internal class ScreenEofReceiver : Closeable {
    val control = AirPlayRecordLifecycleTest.Peer()
    private val video = ServerSocket(0, 10, InetAddress.getLoopbackAddress())
    private val worker = Executors.newCachedThreadPool()
    private val owner = AtomicReference<Socket?>()
    private val connections = CopyOnWriteArrayList<VideoConnection>()
    private val connected = LinkedBlockingQueue<VideoConnection>()
    val unexpectedEof = CountDownLatch(1)
    val heldTeardowns = LinkedBlockingQueue<AirPlayRecordLifecycleTest.Request>()

    @Volatile var rejectTeardown = false

    @Volatile var holdTeardown = false

    @Volatile var lastTeardown: Map<*, *>? = null
    private val closing = AtomicBoolean()

    internal class VideoConnection(
        val socket: Socket,
        val owner: Socket?,
    ) {
        val expectedClose = AtomicBoolean()
        val ended = CountDownLatch(1)
    }

    init {
        control.reply = { request ->
            owner.set(request.connection.socket)
            when (request.method) {
                "SETUP" ->
                    request.respond(
                        200,
                        BinaryPlist.encode(mapOf("streams" to listOf(mapOf("dataPort" to video.localPort.toLong())))),
                    )
                "TEARDOWN" -> {
                    if (holdTeardown) {
                        heldTeardowns.offer(request)
                    } else if (rejectTeardown) {
                        request.respond(500)
                    } else {
                        acknowledgeRetirement(request)
                    }
                }
                else -> request.respond(200)
            }
        }
        worker.execute {
            try {
                while (!closing.get()) {
                    val state = VideoConnection(video.accept(), owner.get())
                    connections += state
                    connected.offer(state)
                    worker.execute {
                        try {
                            val input = state.socket.getInputStream()
                            val bytes = ByteArray(8192)
                            while (input.read(bytes) >= 0) Unit
                        } catch (_: java.io.IOException) {
                        } finally {
                            if (!closing.get() && !state.expectedClose.get()) {
                                unexpectedEof.countDown()
                                state.owner?.close()
                            }
                            state.socket.close()
                            state.ended.countDown()
                        }
                    }
                }
            } catch (_: java.io.IOException) {
            }
        }
    }

    fun acknowledgeRetirement(request: AirPlayRecordLifecycleTest.Request) {
        val body = BinaryPlist.parse(request.body) as? Map<*, *>
        val streams = body?.get("streams") as? List<*>
        lastTeardown = streams?.firstOrNull() as? Map<*, *>
        if (streams == null || streams.any { ((it as? Map<*, *>)?.get("type") as? Number)?.toInt() == 110 }) {
            connections.filter { it.owner === request.connection.socket }.forEach {
                it.expectedClose.set(true)
                it.socket.close() // Like a receiver retiring its video before acknowledging TEARDOWN.
            }
        }
        request.respond(200)
    }

    fun client(): AirPlayClient =
        AirPlayClient(
            Iap2SenderSession.CarPlayEndpoint("test", "", 0, 0, control.port, null, null, null, listOf("127.0.0.1")),
            {},
        )

    fun sender(
        client: AirPlayClient,
        start: Boolean = true,
    ): Pair<VideoSender, VideoConnection> {
        val stream = checkNotNull(client.runScreenStream(ByteArray(32), "display-test"))
        val sender =
            VideoSender(
                "127.0.0.1",
                stream.dataPort,
                stream.cipher,
                640,
                480,
                log = {},
                externalVideoSource =
                    object : ExternalVideoSource {
                        override fun requestKeyFrame() = true
                    },
            )
        check(if (start) sender.start {} else sender.openDataPort())
        return sender to checkNotNull(connected.poll(2, TimeUnit.SECONDS))
    }

    override fun close() {
        closing.set(true)
        video.close()
        connections.forEach { it.socket.close() }
        control.close()
        worker.shutdownNow()
        worker.awaitTermination(2, TimeUnit.SECONDS)
    }
}
