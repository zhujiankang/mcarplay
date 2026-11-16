package com.mcarplay.app.carlife

/** 只保留最近一个可解码 GOP；限额溢出后等待新 IDR，不拼接缺参考帧的片段。 */
internal class CarLifePreroll(
    private val maxFrames: Int = 48,
    private val maxBytes: Int = 6 * 1024 * 1024,
) {
    private val gate = CarLifeJoinGate()
    private val frames = mutableListOf<CarLifeVideoAccessUnit>()
    private var bytes = 0
    private var sps: ByteArray? = null
    private var pps: ByteArray? = null

    @Synchronized
    fun offer(unit: CarLifeVideoAccessUnit) {
        if (unit.keyFrame) clear()
        if (frames.isNotEmpty() &&
            (
                unit.sps?.let { !it.contentEquals(sps) } == true ||
                    unit.pps?.let { !it.contentEquals(pps) } == true
            )
        ) {
            clear()
            return
        }
        val frame = gate.admit(unit) ?: return
        if (frames.size >= maxFrames || frame.data.size > maxBytes - bytes) {
            clear()
            return
        }
        // 源缓冲由其它线程拥有，缓存明确保有自己的字节。
        frames += frame.copy(data = frame.data.copyOf(), sps = frame.sps?.copyOf(), pps = frame.pps?.copyOf())
        sps = frame.sps?.copyOf() ?: sps
        pps = frame.pps?.copyOf() ?: pps
        bytes += frame.data.size
    }

    @Synchronized
    fun drain(): List<CarLifeVideoAccessUnit> = frames.toList().also { clear() }

    @Synchronized
    fun clear() {
        frames.clear()
        bytes = 0
        sps = null
        pps = null
        gate.reset()
    }
}
