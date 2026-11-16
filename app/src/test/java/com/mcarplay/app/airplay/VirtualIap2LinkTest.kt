package com.mcarplay.app.airplay

import com.mcarplay.app.iap2.Iap2Csm
import com.mcarplay.app.iap2.Iap2LinkEngine
import com.mcarplay.app.iap2.MfiLengthMemory
import com.mcarplay.app.iap2.ReceiverProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MIB3 WiFi 隧道握手（VirtualIap2Link 服务端 × 裸引擎扮演车机客户端）。
 *
 * 时序即 AADisplay 以 Frida 抓到的真机行为：手机 arm（发一次 detect）→ 车机 SYN →
 * 手机 SYN|ACK →（链路 NORMAL）手机 0x1D00 → 车机 0x1D01 → 手机 0x1D02+0xAA00 →
 * （0xAA06 → 0xAA00）→ 0xAA01 → 手机 0xAA02 挑战 **20B** → 车机 0xAA03 → 手机
 * 0xAA05 + 突发 → 车机订阅（0x5000/0x4157/0x4154/0x4170）→ 对应空态应答。
 */

/**
 * 按链路帧头拆分（FF5A + u16 长度 + 9B 头）：引擎一次 takeOutput 可能拼多帧，
 * marker（6B，非 FF5A）跳过。
 */
private fun splitLinkFrames(chunk: ByteArray): List<ByteArray> {
    val out = ArrayList<ByteArray>()
    var off = 0
    while (off + 9 <= chunk.size) {
        if (chunk[off] != 0xFF.toByte() || chunk[off + 1] != 0x5A.toByte()) {
            off += 1
            continue
        }
        // 长度字段 = 整帧长度（含 9B 头），非载荷长
        val frameLen = ((chunk[off + 2].toInt() and 0xFF) shl 8) or (chunk[off + 3].toInt() and 0xFF)
        // 帧结构：9B 头（含头校验）+ 载荷 + 1B 尾部载荷校验（len=整帧长度）；
        // 裸 ACK/回显帧无载荷（整帧恰 9B）——跳过而非中断，同块其后的数据帧照常解析
        if (frameLen < 9 || off + frameLen > chunk.size) break
        if (frameLen > 9) out.add(chunk.copyOfRange(off + 9, off + frameLen - 1))
        off += frameLen
    }
    return out
}

class VirtualIap2LinkTest {
    private class Harness(
        strict: Boolean = false,
    ) {
        val controlSent = ArrayList<ByteArray>()
        val allControl = ArrayList<ByteArray>()
        val eventSent = ArrayList<ByteArray>()
        var car = Iap2LinkEngine()
        val writeGuards = ArrayList<() -> Boolean>()
        val link =
            VirtualIap2Link(
                log = { },
                sendEventIap = {
                    eventSent.add(it)
                    true
                },
                sendControlIap = {
                    controlSent.add(it)
                    allControl.add(it)
                },
                tunnelWriter = { null },
                // 关闭节拍线程：测试内确定性驱动（无重传干扰）
                alive = { true },
                tickAutomatically = false,
                nowPlayingProvider = { null },
                nowMillis = { clock },
                requireChallengeResponse = strict,
                sendControlForRound = { bytes, current ->
                    writeGuards.add(current)
                    if (current()) {
                        controlSent.add(bytes)
                        allControl.add(bytes)
                    }
                },
            )
        var clock = 0L
        var now = 1L

        /** 车机→手机：喂字节并冲刷链路出站。 */
        fun carToLink() {
            val out = car.takeOutput()
            if (out.isNotEmpty()) link.onCarBytes(out)
        }

        /** 手机→车机：把控制通道出站字节喂回车机引擎。 */
        fun linkToCar() {
            while (controlSent.isNotEmpty()) {
                val batch = controlSent.toList()
                controlSent.clear()
                batch.forEach { car.feed(it, now) }
            }
        }

        fun exchange() {
            carToLink()
            linkToCar()
            now += 600
            clock += 600
            // 车机侧驱动计时器：采纳对端表后按超时机制补裸 ACK（真机行为同此——
            // BT 日志里我们的 SYN|ACK 之后车机立即回 ACK）
            car.advanceTime(now)
            linkToCar()
        }

        fun exchangeUntilNormal(rounds: Int = 8) {
            repeat(rounds) {
                exchange()
                if (car.state == Iap2LinkEngine.State.NORMAL) return
            }
        }

        /** 车机发一条 CSM，返回手机随后经控制通道发出的 CSM 消息号序列。 */
        fun carSends(
            messageId: Int,
            body: ByteArray = ByteArray(0),
        ): List<Int> {
            val before = controlFrames().size
            assertTrue(car.sendControl(Iap2Csm.encode(messageId, body), now))
            exchange()
            val ids = controlFrames().drop(before).map { it.second }
            return ids
        }

        /** 控制通道出站 →（链路帧拆分）→ 完整 CSM 帧字节与其消息号。
         *  每个链路帧载荷恰好一条 CSM：必须逐帧新建 Parser——SYN|ACK 的 LSP 载荷
         *  不是 CSM，共用有状态解析器会污染后续帧的边界。 */
        fun controlFrames(): List<Pair<ByteArray, Int>> {
            val out = ArrayList<Pair<ByteArray, Int>>()
            for (chunk in allControl) {
                splitLinkFrames(chunk).forEach { payload ->
                    Iap2Csm.Parser().offer(payload).forEach { frame ->
                        out.add(payload to frame.messageId)
                    }
                }
            }
            return out
        }

        /** 最近一条指定消息号的完整 CSM 帧字节（Frida 常量逐字节比对用）。 */
        fun lastRawFrameOf(messageId: Int): ByteArray? = controlFrames().lastOrNull { it.second == messageId }?.first

        /** 已发 0xAA02 挑战的长度序列（param 0 长度；多条时按出现顺序）。 */
        fun challengeLengths(): List<Int> =
            controlFrames()
                .filter { it.second == 0xAA02 }
                .map { (raw, _) ->
                    Iap2Csm
                        .parseParams(raw.copyOfRange(6, raw.size))
                        .first()
                        .value.size
                }
    }

    @Test fun verifiedMercedesWaitsForPeerSubscriptionAndEmitsTerminatedStringsOnly() {
        val h = Harness(strict = true)
        var authenticated = 0
        var completed = 0
        h.link.onAuthenticationComplete = { authenticated++ }
        h.link.onControlSessionComplete = { completed++ }
        h.link.armMib3()
        h.car.start(0)
        h.exchangeUntilNormal()
        h.carSends(0x1D01, Iap2Csm.string(0, "Mercedes 017 verified"))
        assertTrue(h.carSends(0xAA03, Iap2Csm.param(0, byteArrayOf(1))).isEmpty())
        assertEquals(0, authenticated)
        h.carSends(0xAA01, Iap2Csm.param(0, ByteArray(613)))
        val burst = h.carSends(0xAA03, Iap2Csm.param(0, ByteArray(68)))
        assertEquals(listOf(0xAA05, 0x4E0C, 0x4E0A, 0x4E0B, 0x4E09), burst)
        assertEquals(1, authenticated)
        assertEquals(0, completed)
        for (id in listOf(0x4E0C, 0x4E0A, 0x4E09)) {
            val frame = Iap2Csm.Parser().offer(h.lastRawFrameOf(id)!!).single()
            assertEquals(
                0,
                Iap2Csm
                    .parseParams(frame.body)
                    .first()
                    .value
                    .last()
                    .toInt(),
            )
        }
        h.carSends(0x5000)
        h.carSends(0x4157)
        assertEquals(1, completed)
    }

    @Test fun verifiedMercedesNeverInfersAuthenticationFromSilence() {
        val h = Harness(strict = true)
        h.link.pinnedMfiLength = 32
        var completed = 0
        h.link.onControlSessionComplete = { completed++ }
        h.link.armMib3()
        h.car.start(0)
        h.exchangeUntilNormal()
        h.carSends(0x1D01, Iap2Csm.string(0, "Mercedes 017 silent"))
        h.carSends(0xAA01, Iap2Csm.param(0, ByteArray(613)))
        h.clock += VirtualIap2Link.PINNED_MFI_WINDOW_MS
        h.link.onCarBytes(Iap2LinkEngine.MARKER)
        assertTrue(h.controlFrames().none { it.second == 0xAA05 || it.second == 0x4E0C })
        assertTrue(h.carSends(0x5000).isEmpty())
        assertEquals(0, completed)
    }

    private fun reset(h: Harness) {
        val frame = byteArrayOf(0xff.toByte(), 0x5a, 0, 9, 0x10, 0, 0, 0, 0)
        frame[8] = (-frame.take(8).sumOf { it.toInt() and 255 }).toByte()
        h.link.onCarBytes(frame)
    }

    @Test fun deadControlLinkRecoversAfterCooldownWithFreshIdentityAndInvalidatesQueuedWrites() {
        val h = Harness()
        var resets = 0
        h.link.onControlSessionReset = { resets++ }
        h.link.armMib3()
        h.car.start(0)
        h.exchangeUntilNormal()
        h.carSends(0x1D01, Iap2Csm.string(0, "First"))
        val guards = h.writeGuards.toList()
        reset(h)
        assertEquals(1, resets)
        assertTrue(guards.none { it() })
        val before = h.allControl.size
        h.clock += 4999
        h.link.tick()
        assertEquals(before, h.allControl.size)
        h.clock++
        h.link.tick()
        assertEquals(before + 1, h.allControl.size)
        assertTrue(h.allControl.last().contentEquals(Iap2LinkEngine.MARKER))
        h.controlSent.clear()
        h.car = Iap2LinkEngine()
        h.car.start(h.clock)
        h.exchangeUntilNormal()
        val replies = h.carSends(0x1D01, Iap2Csm.string(0, "Second"))
        assertTrue(replies.containsAll(listOf(0x1D02, 0xAA00)))
    }

    @Test fun peerResetDoesNotRecordAnInterruptedChallengeAsASilentFormat() {
        val h = Harness()
        val identity = "Mercedes interrupted EC017 tunnel"
        h.link.armMib3()
        h.car.start(0)
        h.exchangeUntilNormal()
        h.carSends(0x1D01, Iap2Csm.string(0, "Mercedes interrupted EC017"))
        val oid = byteArrayOf(6, 7, 0x2a, 0x86.toByte(), 0x48, 0xce.toByte(), 0x3d, 2, 1)
        h.carSends(0xAA01, Iap2Csm.param(0, oid))
        assertEquals(listOf(32), h.challengeLengths())
        reset(h)
        h.clock += 4000
        h.link.onCarBytes(ByteArray(0))
        assertTrue(32 !in MfiLengthMemory.load(null, identity).silent)
    }

    @Test fun recoveryBudgetStopsRepeatedResetsFromProducingAnUnboundedHandshakeLoop() {
        val h = Harness()
        h.link.armMib3()
        h.car.start(0)
        h.exchangeUntilNormal()
        repeat(2) {
            reset(h)
            h.clock += 35_000
            h.link.tick()
            h.controlSent.clear()
            h.car = Iap2LinkEngine()
            h.car.start(h.clock)
            h.exchangeUntilNormal()
        }
        reset(h)
        val before = h.allControl.size
        h.clock += 60_000
        repeat(10) { h.link.tick() }
        assertEquals(before, h.allControl.size)
    }

    @Test fun emptyCertificateDoesNotStartChallengeAndEcHintPrefers32Bytes() {
        val h = Harness()
        h.link.setGeneration(ReceiverProfile.LinkGeneration.CLASSIC_MIB3)
        h.link.armMib3()
        h.car.start(0)
        h.exchangeUntilNormal()
        h.carSends(0x1D01, Iap2Csm.string(0, "Mercedes 016 empty certificate") + Iap2Csm.string(1, "MB_D-M-5"))
        assertEquals(emptyList<Int>(), h.carSends(0xAA01))
        assertEquals(emptyList<Int>(), h.challengeLengths())
        val oid = byteArrayOf(6, 7, 0x2a, 0x86.toByte(), 0x48, 0xce.toByte(), 0x3d, 2, 1)
        // Public-key type hint only; this fixture is not a certificate/trust validation test.
        h.carSends(0xAA01, Iap2Csm.param(0, byteArrayOf(0x30, 0x10) + oid))
        assertEquals(listOf(32), h.challengeLengths())
        h.carSends(0xAA01, Iap2Csm.param(0, oid))
        assertEquals(listOf(32), h.challengeLengths())
    }

    @Test fun ecPublicKeyHintRejectsTruncatedOrDifferentOid() {
        val oid = byteArrayOf(6, 7, 0x2a, 0x86.toByte(), 0x48, 0xce.toByte(), 0x3d, 2, 1)
        assertTrue(VirtualIap2Link.containsEcdsaPublicKeyOid(oid))
        assertTrue(!VirtualIap2Link.containsEcdsaPublicKeyOid(oid.dropLast(1).toByteArray()))
        assertTrue(!VirtualIap2Link.containsEcdsaPublicKeyOid(oid.copyOf().also { it[it.lastIndex] = 2 }))
    }

    @Test
    fun singleDetectRepliesWithoutPrematureSynOrPeriodicRetransmission() {
        val h = Harness()
        h.link.armMib3()
        h.controlSent.clear()
        h.link.onCarBytes(Iap2LinkEngine.MARKER)
        assertEquals(listOf(Iap2LinkEngine.MARKER.toList()), h.controlSent.map { it.toList() })
        h.clock += 5_000
        h.link.onCarBytes(ByteArray(0))
        assertEquals(1, h.controlSent.size)
        assertTrue(h.eventSent.isEmpty())
    }

    @Test
    fun fragmentedAndCoalescedDetectHaveIdenticalProtocolOutput() {
        val bytes = Iap2LinkEngine.MARKER + Iap2LinkEngine.MARKER

        fun run(chunks: List<ByteArray>): List<Byte> {
            val h = Harness()
            h.link.armMib3()
            h.controlSent.clear()
            chunks.forEach(h.link::onCarBytes)
            return h.controlSent.flatMap { it.toList() }
        }
        val expected = run(listOf(Iap2LinkEngine.MARKER, Iap2LinkEngine.MARKER))
        assertTrue(expected.size > Iap2LinkEngine.MARKER.size)
        assertEquals(expected, run(listOf(bytes)))
        for (split in 1 until bytes.size) {
            assertEquals("split=$split", expected, run(listOf(bytes.copyOfRange(0, split), bytes.copyOfRange(split, bytes.size))))
        }
        assertEquals(expected, run(bytes.map { byteArrayOf(it) }))
    }

    @Test
    fun malformedDetectDoesNotTriggerSynOrCountAsPeerProgress() {
        val h = Harness()
        h.link.armMib3()
        h.controlSent.clear()
        h.link.onCarBytes(Iap2LinkEngine.MARKER.copyOf().also { it[5] = 0 })
        assertTrue(h.controlSent.isEmpty())
        h.link.onCarBytes(Iap2LinkEngine.MARKER)
        assertEquals(listOf(Iap2LinkEngine.MARKER.toList()), h.controlSent.map { it.toList() })
    }

    @Test
    fun mib3ArmSendsExactlyOneDetectOnControlChannel() {
        val h = Harness()
        h.link.setGeneration(ReceiverProfile.LinkGeneration.CLASSIC_MIB3)
        h.link.armMib3()
        h.link.armMib3()
        assertEquals(listOf(Iap2LinkEngine.MARKER.toList()), h.controlSent.map { it.toList() })
        assertTrue(h.eventSent.isEmpty())
    }

    @Test
    fun preArmMarkerGetsNoReply() {
        val h = Harness()
        h.link.begin()
        h.link.onCarBytes(Iap2LinkEngine.MARKER)
        assertTrue(h.controlSent.isEmpty())
        assertTrue(h.eventSent.isEmpty())
    }

    /**
     * Honda HFT（2026-10-20 实测）：车机在事件通道上 1Hz 反复发 marker，说明它卡在
     * 探测态、在等**我们**的会话表。连着第 2 个 marker 起必须升级为「marker + 本表
     * 同步包」，且**只能走控制通道**——出站写在事件通道上车机不读（r6 踩过的坑）。
     *
     * 2026-10-23 二次定谳：该行为是**全代际**的（不再按 EVENT_LINK 分派）——奥迪
     * 同样要求链路 NORMAL，否则 RECORD 后 ~10s 被拆（0.1.9/0.1.17 双日志实证）。
     * 当年「协商挂起奥迪 UI」的真因是协商完成后缺 modesChanged 重申。
     */
    @Test
    fun repeatedMarkersUpgradeToControlChannelHandshake() {
        val h = Harness()
        h.link.setGeneration(ReceiverProfile.LinkGeneration.EVENT_LINK)
        h.link.armMib3()
        val detect = h.controlSent.size
        // 第 1 个：还不判定（MIB3 会再发一次探测重发）
        h.link.onCarBytes(Iap2LinkEngine.MARKER)
        assertEquals("首个 DETECT 应单次回应", detect + 1, h.controlSent.size)
        // 第 2 个：升级为 marker + 同步包
        h.link.onCarBytes(Iap2LinkEngine.MARKER)
        val sent = h.controlSent.drop(detect + 1)
        assertTrue("重复 marker 后必须补发协商字节", sent.isNotEmpty())
        val payload = sent.fold(ByteArray(0)) { acc, bytes -> acc + bytes }
        assertTrue(
            "补发的第一批必须含 marker（FF 55）",
            payload.size >= 6 && payload[0] == 0xFF.toByte() && payload[1] == 0x55.toByte(),
        )
        assertTrue(
            "marker 之后必须紧跟 FF5A 会话表（车机等的就是它）",
            payload.size >= 12 && payload[6] == 0xFF.toByte() && payload[7] == 0x5A.toByte(),
        )
        assertTrue(
            "会话表必须带 SYN 位（0x80）",
            (payload[10].toInt() and 0x80) != 0,
        )
        assertTrue("出站绝不能落到事件通道", h.eventSent.isEmpty())
    }

    /**
     * 2026-10-23 二次定谳：**所有代际都必须响应 marker 完成链路协商**。车机的
     * marker 以 1Hz 无限重发（0.1.17 车主日志：远超旧注释的「2~5 个后静默」），
     * 不回应则链路永远到不了 NORMAL，RECORD 后 ~10s 被车机拆整条会话（0.1.9 实测
     * 10.17s、0.1.17 实测 10.18s）。当年「协商挂起奥迪 UI」的真因是协商完成后缺
     * modesChanged 重申（SessionController.scheduleModesReclaim）。
     */
    @Test
    fun markersEscalateRegardlessOfGeneration() {
        for (generation in ReceiverProfile.LinkGeneration.entries) {
            val h = Harness()
            h.link.setGeneration(generation)
            h.link.armMib3()
            val detect = h.controlSent.size
            // 第 1 个：还不判定（车机会再发探测重发）
            h.link.onCarBytes(Iap2LinkEngine.MARKER)
            assertEquals("$generation 首个 DETECT 应单次回应", detect + 1, h.controlSent.size)
            // 第 2 个：升级为 marker + 会话表
            h.link.onCarBytes(Iap2LinkEngine.MARKER)
            assertTrue(
                "$generation 第 2 个 marker 必须触发协商字节（0.1.17 证伪「忽略」路线）",
                h.controlSent.size > detect,
            )
            assertTrue("$generation 出站不得落到事件通道", h.eventSent.isEmpty())
        }
    }

    @Test
    fun fullMib3HandshakeWithBurstAndSubscriptions() {
        val h = Harness()
        var authenticated = 0
        h.link.onAuthenticationComplete = { authenticated++ }
        h.link.setGeneration(ReceiverProfile.LinkGeneration.CLASSIC_MIB3)
        h.link.armMib3()
        h.car.start(0)
        h.exchangeUntilNormal()
        assertEquals(Iap2LinkEngine.State.NORMAL, h.car.state)
        // detect(1) + SYN|ACK + 0x1D00 已走控制通道；车机已收到 0x1D00
        assertTrue(h.controlFrames().map { it.second }.contains(0x1D00))

        // 0x1D01 → 0x1D02 + 0xAA00
        assertEquals(
            listOf(0x1D02, 0xAA00),
            h.carSends(0x1D01, Iap2Csm.string(0, "Audi_MMI_3315") + Iap2Csm.string(1, "AUDI 5163")),
        )

        // 0xAA06（auth serial）→ 再发 0xAA00
        assertEquals(listOf(0xAA00), h.carSends(0xAA06, Iap2Csm.string(0, "serial")))

        // 0xAA01（证书）→ 0xAA02 挑战 20B（MIB3 真机长度；DiPlay 形态为 32B）
        val challengeFrames = h.carSends(0xAA01, Iap2Csm.param(0, ByteArray(642)))
        assertEquals(listOf(0xAA02), challengeFrames)
        val challengeBody =
            h
                .lastRawFrameOf(0xAA02)!!
                .copyOfRange(6, h.lastRawFrameOf(0xAA02)!!.size)
                .let { Iap2Csm.parseParams(it).first() }
        assertEquals(20, challengeBody.value.size)
        assertEquals(0, authenticated)

        // 0xAA03（签名应答）→ 0xAA05 + 突发（0x4E0C/0xA100/0x4E0A/0x4E0B/0x4E09/0xFFFA/0xFFF0）
        assertEquals(
            listOf(0xAA05, 0x4E0C, 0xA100, 0x4E0A, 0x4E0B, 0x4E09, 0xFFFA, 0xFFF0),
            h.carSends(0xAA03, Iap2Csm.param(0, ByteArray(96))),
        )
        assertEquals(1, authenticated)

        // 订阅应答：0x5000 → 0x5001 空态；0x4157 → 0x4158；0x4154 → 0x4155；0x4170 → 0x4171
        assertEquals(listOf(0x5001), h.carSends(0x5000))
        assertEquals(listOf(0x4158), h.carSends(0x4157))
        assertEquals(listOf(0x4155), h.carSends(0x4154))
        assertEquals(listOf(0x4171), h.carSends(0x4170))
    }

    @Test
    fun nowPlayingIdleReplyHasStoppedStateAndClearsArtworkWhileOtherRepliesKeepTheirWireShape() {
        val h = Harness()
        h.link.setGeneration(ReceiverProfile.LinkGeneration.CLASSIC_MIB3)
        h.link.armMib3()
        h.car.start(0)
        h.exchangeUntilNormal()
        h.carSends(0x1D01, Iap2Csm.string(0, "x"))
        h.carSends(0xAA01, Iap2Csm.param(0, ByteArray(642)))
        h.carSends(0xAA03, Iap2Csm.param(0, ByteArray(96)))
        h.carSends(0x5000)
        val frame = Iap2Csm.Parser().offer(h.lastRawFrameOf(0x5001)!!).single()
        val groups = Iap2Csm.parseParams(frame.body).associateBy { it.id }
        val item = Iap2Csm.parseParams(groups.getValue(0).value).associateBy { it.id }
        val playback = Iap2Csm.parseParams(groups.getValue(1).value).associateBy { it.id }
        assertEquals(listOf<Byte>(0), playback.getValue(0).value.toList())
        assertEquals(listOf<Byte>(0), item.getValue(26).value.toList())
        assertEquals(listOf<Byte>(0), item.getValue(1).value.toList())
        assertEquals(
            VirtualIap2Link.MIB3_COMMUNICATIONS_UPDATE_IDLE.toList(),
            run {
                h.carSends(0x4157)
                h.lastRawFrameOf(0x4158)!!.toList()
            },
        )
    }

    @Test
    fun tunnelMfiUsesRememberedLengthPerCar() {
        // 与其它测试不同身份，避免进程内记忆串扰
        val identity = "MemCar MemModel MemMfr tunnel"
        MfiLengthMemory.recordSuccess(null, identity, 32)
        val h = Harness()
        h.link.setGeneration(ReceiverProfile.LinkGeneration.CLASSIC_MIB3)
        h.link.armMib3()
        h.car.start(0)
        h.exchangeUntilNormal()
        h.carSends(0x1D01, Iap2Csm.string(0, "MemCar") + Iap2Csm.string(1, "MemModel") + Iap2Csm.string(2, "MemMfr"))
        h.carSends(0xAA01, Iap2Csm.param(0, ByteArray(642)))
        // 记忆命中：32B 直接排第一，不再从 20B 试起
        assertEquals(listOf(32), h.challengeLengths())
    }

    @Test
    fun silentChallengeRetriesNextLengthWithinSession() {
        val h = Harness()
        h.link.setGeneration(ReceiverProfile.LinkGeneration.CLASSIC_MIB3)
        h.link.armMib3()
        h.car.start(0)
        h.exchangeUntilNormal()
        h.carSends(0x1D01, Iap2Csm.string(0, "SilCar") + Iap2Csm.string(1, "SilMfr"))
        h.carSends(0xAA01, Iap2Csm.param(0, ByteArray(642)))
        assertEquals(listOf(20), h.challengeLengths())
        // 20B 静默：推进时钟越过 6s 窗口，喂一个 marker 触发节拍检查（drainLocked）
        h.clock += 7_000
        h.link.onCarBytes(Iap2LinkEngine.MARKER)
        assertEquals(listOf(20, 32), h.challengeLengths())
        // 32B 得到应答 → 0xAA05 + 突发照常
        val after = h.controlFrames().size
        h.carSends(0xAA03, Iap2Csm.param(0, ByteArray(96)))
        assertTrue(
            "frames=${h.controlFrames().drop(after).map { it.second }}",
            h
                .controlFrames()
                .drop(after)
                .map { it.second }
                .containsAll(listOf(0xAA05, 0x4E0C, 0xFFFA)),
        )
    }

    /** 固定长度：只发一次 32B，静默到窗口结束也不追加挑战，直接 0xAA05（不让车机排队多个认证）。 */
    @Test
    fun pinnedChallengeIsSentOnceAndNeverQueuesMore() {
        val h = Harness()
        h.link.pinnedMfiLength = 32
        h.link.setGeneration(ReceiverProfile.LinkGeneration.CLASSIC_MIB3)
        h.link.armMib3()
        h.car.start(0)
        h.exchangeUntilNormal()
        h.carSends(0x1D01, Iap2Csm.string(0, "PinCar") + Iap2Csm.string(1, "PinMfr"))
        h.carSends(0xAA01, Iap2Csm.param(0, ByteArray(642)))
        assertEquals(listOf(32), h.challengeLengths())
        // 20B 档的 6 s 窗口早已过去也不换档
        h.clock += 7_000
        h.link.onCarBytes(Iap2LinkEngine.MARKER)
        assertEquals(listOf(32), h.challengeLengths())
        h.clock += VirtualIap2Link.PINNED_MFI_WINDOW_MS
        h.link.onCarBytes(Iap2LinkEngine.MARKER)
        assertEquals(listOf(32), h.challengeLengths())
        assertTrue(h.controlFrames().any { it.second == 0xAA05 })
    }

    /**
     * 0x6802 取参数：本田 HFT 实测帧 `00 06 00 00 00 03 | 00 05 00 01 04`。
     *
     * param 0 恒为 `00 03`（报告类型），param 1 才是 HID 报文（0x04 = 下一首）。
     * 旧实现取「第一个短参数」→ 永远拿到 00 03 → 位图全 0 → 按键点不出动作。
     */
    @Test
    fun hidReportComesFromParamOneNotParamZero() {
        // 单行书写：ktlint 对该规则要求「多行则一参数一行」，单行更贴近原始帧的可读形式
        val body = byteArrayOf(0x00, 0x06, 0x00, 0x00, 0x00, 0x03, 0x00, 0x05, 0x00, 0x01, 0x04)
        val report = VirtualIap2Link.hidReportFromParams(Iap2Csm.parseParams(body))
        assertEquals(listOf<Byte>(0x04), report?.toList())
    }

    @Test
    fun hidReportFallsBackToLastShortParamWhenOnlyOneGiven() {
        val body = byteArrayOf(0x00, 0x05, 0x00, 0x00, 0x08)
        val report = VirtualIap2Link.hidReportFromParams(Iap2Csm.parseParams(body))
        assertEquals(listOf<Byte>(0x08), report?.toList())
    }
}
