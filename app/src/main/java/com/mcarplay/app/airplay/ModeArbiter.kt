package com.mcarplay.app.airplay

/**
 * Adapted from CarPToCarL AirPlayResourceState (MIT, Copyright (c) 2026 lizhihua).
 * One ownership model for main screen/audio: permanent entity, named/anonymous car loans and phone audio borrowing.
 * Network publication runs outside the arbiter lock; acknowledgement commits only the current revision.
 */
internal class ModeArbiter {
    enum class Entity(
        val code: Long,
    ) {
        CONTROLLER(1L),
        ACCESSORY(2L),
    }

    enum class CarAudioTransfer { TAKEN_PERMANENTLY, BORROWED_CONSTRAINED, UNBORROWED_TO_PHONE }

    private data class Resource(
        var permanent: Entity = Entity.CONTROLLER,
        var takeConstraint: Int = CONSTRAINT_ANYTIME,
        var borrowConstraint: Int = CONSTRAINT_ANYTIME,
        val borrowers: MutableList<Pair<String?, Int>> = mutableListOf(),
        var phoneBorrowing: Boolean = false,
    ) {
        val borrowed: Boolean get() = borrowers.isNotEmpty()
        val constraint: Int get() = borrowers.maxOfOrNull { it.second } ?: takeConstraint
        val owner: Entity get() =
            when {
                borrowed -> Entity.ACCESSORY
                phoneBorrowing -> Entity.CONTROLLER
                else -> permanent
            }

        fun duplicate() = copy(borrowers = borrowers.toMutableList())

        fun claim(priority: Int): Boolean {
            if (borrowed && !allows(priority, constraint)) return false
            if (permanent != Entity.CONTROLLER && !allows(priority, takeConstraint)) return false
            permanent = Entity.CONTROLLER
            takeConstraint = CONSTRAINT_ANYTIME
            borrowConstraint = CONSTRAINT_ANYTIME
            borrowers.clear()
            phoneBorrowing = false
            return true
        }

        fun borrow(priority: Int): Boolean {
            if (owner == Entity.CONTROLLER) return true
            if (borrowed || !allows(priority, borrowConstraint)) return false
            phoneBorrowing = true
            return true
        }
    }

    private val resources = linkedMapOf(SCREEN to Resource(), MAIN_AUDIO to Resource())
    private val carAppStates = linkedMapOf(2L to 0L, 3L to 0L, 1L to 0L)
    private var carSpeechMode = -1L
    private var screenRelinquished = false
    private var audioClaimPending = false
    private var speechRecognition = false
    private var revision = 0L
    private val audioTransfers = ArrayDeque<CarAudioTransfer>()

    /** Temporary loans keep the phone's permanent screen ownership; native Home TAKE does not. */
    @get:Synchronized val screenPermanentlyOwnedByPhone: Boolean get() = resources.getValue(SCREEN).permanent == Entity.CONTROLLER

    @get:Synchronized val phoneOwnsScreen: Boolean get() = resources.getValue(SCREEN).owner == Entity.CONTROLLER

    @get:Synchronized val phoneOwnsAudio: Boolean get() = resources.getValue(MAIN_AUDIO).owner == Entity.CONTROLLER

    @get:Synchronized val phoneBorrowsAudio: Boolean get() = resources.getValue(MAIN_AUDIO).phoneBorrowing

    @get:Synchronized val recognizingSpeech: Boolean get() = speechRecognition || carSpeechMode == 2L

    @get:Synchronized val audioClaimAwaitingReturn: Boolean get() = audioClaimPending

    @get:Synchronized val screenCanReturnOnInvite: Boolean get() {
        val screen = resources.getValue(SCREEN)
        return screen.owner == Entity.ACCESSORY && !screen.borrowed && allows(PRIORITY_USER_INITIATED, screen.takeConstraint)
    }

    fun acquireScreen(
        fromRequestUi: Boolean,
        publish: (Map<String, Any?>) -> Boolean,
    ): Boolean =
        claimAndPublish(SCREEN, publish) {
            (!it.borrowed || fromRequestUi) && it.claim(PRIORITY_USER_INITIATED)
        }

    fun acquireAudio(
        priority: Int,
        temporary: Boolean,
        publish: (Map<String, Any?>) -> Boolean,
    ): Boolean =
        claimAndPublish(MAIN_AUDIO, publish) { if (temporary) it.borrow(priority) else it.claim(priority) }.also {
            synchronized(this) { audioClaimPending = !it }
        }

    fun releaseAudioBorrow(publish: (Map<String, Any?>) -> Boolean): Boolean =
        claimAndPublish(MAIN_AUDIO, publish) { audio -> audio.phoneBorrowing.also { audio.phoneBorrowing = false } }

    private fun claimAndPublish(
        id: Long,
        publish: (Map<String, Any?>) -> Boolean,
        change: (Resource) -> Boolean,
    ): Boolean {
        val expected: Long
        val next: Resource
        val announcement: Map<String, Any?>
        synchronized(this) {
            val current = resources.getValue(id)
            next = current.duplicate()
            if (!change(next)) return false
            expected = revision
            resources[id] = next
            announcement = parameters()
            resources[id] = current
        }
        if (!publish(announcement)) return false
        synchronized(this) {
            if (revision != expected) return false
            resources[id] = next
            revision++
            return true
        }
    }

    @Synchronized fun setSpeechRecognition(active: Boolean) {
        if (speechRecognition != active) {
            speechRecognition = active
            revision++
        }
    }

    @Synchronized fun applyInitial(modes: Map<*, *>?): String {
        resources.keys.forEach { resources[it] = Resource() }
        carAppStates.keys.forEach { carAppStates[it] = 0L }
        carSpeechMode = -1L
        screenRelinquished = false
        speechRecognition = false
        audioClaimPending = false
        audioTransfers.clear()
        return applyRequests(modes, initial = true)
    }

    @Synchronized fun onChangeModes(params: Map<*, *>?): String = if (applyTransaction(params)) describe() else "请求已拒绝，资源保持不变"

    class UiRequest(
        val note: String,
        val retake: Boolean,
        val rebuildStream: Boolean = false,
        val allowed: Boolean = true,
    )

    /** Preview only. Production callers commit through acquireScreen after the receiver's acknowledgement. */
    @Synchronized fun screenRequest(): UiRequest {
        val screen = resources.getValue(SCREEN)
        if (screen.owner == Entity.CONTROLLER) return UiRequest("主屏本就归手机", true, screenRelinquished)
        val allowed =
            allows(PRIORITY_USER_INITIATED, screen.constraint) &&
                (screen.permanent == Entity.CONTROLLER || allows(PRIORITY_USER_INITIATED, screen.takeConstraint))
        return UiRequest(if (allowed) "用户请求拿回主屏" else "主屏约束不允许拿回", false, screenRelinquished, allowed)
    }

    /** Legacy synchronous adapter for protocol consumers/tests. Live RTSP flow uses screenRequest/acquireScreen. */
    @Synchronized fun onUserRequestedUi(): UiRequest {
        val result = screenRequest()
        if (result.allowed) {
            resources.getValue(SCREEN).claim(PRIORITY_USER_INITIATED)
            revision++
        }
        return result
    }

    class AudioClaim(
        val note: String,
        val claimed: Boolean,
    )

    @Synchronized fun onAudioStreamStarting(): AudioClaim {
        val claimed = resources.getValue(MAIN_AUDIO).claim(PRIORITY_USER_INITIATED)
        audioClaimPending = !claimed
        if (claimed) revision++
        return AudioClaim(if (claimed) "接管主音频" else "主音频约束不允许申请", claimed)
    }

    @Synchronized fun consumeAudioClaimRetry(): Boolean {
        if (!audioClaimPending) return false
        val candidate = resources.getValue(MAIN_AUDIO).duplicate()
        if (!candidate.claim(PRIORITY_USER_INITIATED)) return false
        audioClaimPending = false
        return true
    }

    @Synchronized fun consumeAudioTransfer(): CarAudioTransfer? = if (audioTransfers.isEmpty()) null else audioTransfers.removeFirst()

    @Synchronized fun onUserReturnedToCar(): String {
        revision++
        // Leaving the UI does not select another audio source. Preserve music and all camera loans.
        resources.getValue(SCREEN).apply {
            permanent = Entity.ACCESSORY
            phoneBorrowing = false
            takeConstraint = CONSTRAINT_ANYTIME
        }
        screenRelinquished = true
        return describe()
    }

    @Synchronized fun onScreenStreamRestored() {
        if (phoneOwnsScreen) screenRelinquished = false
    }

    @Synchronized fun resourcesPayload(releaseScreen: Boolean = false): List<Map<String, Any?>> =
        resources.map { (id, resource) ->
            val release = releaseScreen && id == SCREEN
            linkedMapOf(
                "resourceID" to id,
                "entity" to (if (release) Entity.ACCESSORY else resource.owner).code,
                "permanentEntity" to (if (release) Entity.ACCESSORY else resource.permanent).code,
            )
        }

    @Synchronized fun parameters(releaseScreen: Boolean = false): Map<String, Any?> =
        linkedMapOf(
            "appStates" to
                carAppStates.map { (id, entity) ->
                    linkedMapOf<String, Any?>("appStateID" to id, "entity" to if (id == 1L && speechRecognition) 1L else entity).apply {
                        if (id == 1L) put("speechMode", if (speechRecognition) 2L else carSpeechMode)
                    }
                },
            "resources" to resourcesPayload(releaseScreen),
        )

    @Synchronized fun changeResponse(params: Map<*, *>?): Map<String, Any?> =
        if (applyTransaction(params)) {
            linkedMapOf("status" to 0L, "params" to parameters())
        } else {
            linkedMapOf("status" to 1L)
        }

    /** Validate and stage the entire accessory transaction before publishing any state or side effects. */
    private fun applyTransaction(params: Map<*, *>?): Boolean {
        if (!ModeChangeRequest.valid(params)) return false
        val candidate = ModeArbiter()
        resources.forEach { (id, resource) -> candidate.resources[id] = resource.duplicate() }
        candidate.carAppStates.putAll(carAppStates)
        candidate.carSpeechMode = carSpeechMode
        candidate.screenRelinquished = screenRelinquished
        candidate.audioTransfers.addAll(audioTransfers)
        candidate.applyRequests(params, initial = false)
        resources.putAll(candidate.resources)
        carAppStates.putAll(candidate.carAppStates)
        carSpeechMode = candidate.carSpeechMode
        screenRelinquished = candidate.screenRelinquished
        audioTransfers.clear()
        audioTransfers.addAll(candidate.audioTransfers)
        revision++
        return true
    }

    @Synchronized fun describe(): String =
        resources.entries.joinToString(" ") { (id, resource) ->
            val name = if (id == SCREEN) "主屏" else "主音频"
            val owner =
                if (resource.borrowed) {
                    "车机借用"
                } else if (resource.owner == Entity.CONTROLLER) {
                    "手机"
                } else {
                    "车机"
                }
            "$name=$owner(约束 ${resource.constraint})"
        }

    private fun applyRequests(
        container: Map<*, *>?,
        initial: Boolean,
    ): String {
        revision++
        (container?.get("appStates") as? List<*>)?.forEach { raw ->
            val item = raw as? Map<*, *> ?: return@forEach
            val id = (item["appStateID"] as? Number)?.toLong() ?: return@forEach
            if (id !in carAppStates) return@forEach
            if (id == 1L) {
                (item["speechMode"] as? Number)?.toLong()?.let {
                    carSpeechMode = it
                    carAppStates[id] = if (it == -1L) 0L else 2L
                }
            } else {
                (item["state"] as? Boolean)?.let { carAppStates[id] = if (it) 2L else 0L }
            }
        }
        (container?.get("resources") as? List<*>)?.forEach { raw ->
            val item = raw as? Map<*, *> ?: return@forEach
            val id = (item["resourceID"] as? Number)?.toLong() ?: return@forEach
            val resource = resources[id] ?: return@forEach
            val previousOwner = resource.owner
            val wasBorrowed = resource.borrowed
            val type = (item["transferType"] as? Number)?.toInt()
            val constraint =
                (item["unborrowConstraint"] as? Number)?.toInt()
                    ?: (item["borrowConstraint"] as? Number)?.toInt() ?: CONSTRAINT_ANYTIME
            when (type) {
                TRANSFER_TAKE -> {
                    val priority = (item["transferPriority"] as? Number)?.toInt() ?: PRIORITY_NICE_TO_HAVE
                    resource.permanent = if (initial && priority < PRIORITY_USER_INITIATED) Entity.CONTROLLER else Entity.ACCESSORY
                    resource.takeConstraint =
                        if (resource.permanent == Entity.CONTROLLER) {
                            CONSTRAINT_ANYTIME
                        } else {
                            (item["takeConstraint"] as? Number)?.toInt() ?: CONSTRAINT_ANYTIME
                        }
                    resource.borrowConstraint = (item["borrowConstraint"] as? Number)?.toInt() ?: CONSTRAINT_ANYTIME
                    resource.phoneBorrowing = false
                }
                TRANSFER_UNTAKE -> {
                    resource.permanent = Entity.CONTROLLER
                    resource.takeConstraint = CONSTRAINT_ANYTIME
                }
                TRANSFER_BORROW -> {
                    val borrowId = item["borrowID"]?.toString()
                    val existing = if (borrowId == null) -1 else resource.borrowers.indexOfFirst { it.first == borrowId }
                    if (existing < 0) {
                        resource.borrowers += borrowId to constraint
                    } else {
                        resource.borrowers[existing] = borrowId to maxOf(resource.borrowers[existing].second, constraint)
                    }
                }
                TRANSFER_UNBORROW -> {
                    val borrowId = item["borrowID"]?.toString()
                    var index = resource.borrowers.indexOfLast { it.first == borrowId }
                    // Older receivers omit the ID on return. Only an unambiguous single loan may use that fallback.
                    if (index < 0 && borrowId == null && resource.borrowers.size == 1) index = 0
                    if (index >= 0) resource.borrowers.removeAt(index)
                }
                null -> {
                    val permanent = (item["permanentEntity"] as? Number)?.toLong()
                    if (permanent == 1L ||
                        permanent == 2L
                    ) {
                        resource.permanent = if (permanent == 1L) Entity.CONTROLLER else Entity.ACCESSORY
                    }
                    (item["takeConstraint"] as? Number)?.toInt()?.let { resource.takeConstraint = it }
                    (item["borrowConstraint"] as? Number)?.toInt()?.let { resource.borrowConstraint = it }
                }
            }
            if (!initial &&
                id == SCREEN &&
                previousOwner == Entity.CONTROLLER &&
                resource.owner == Entity.ACCESSORY
            ) {
                screenRelinquished = true
            }
            if (!initial && id == MAIN_AUDIO) {
                val transfer =
                    when {
                        type == TRANSFER_TAKE -> CarAudioTransfer.TAKEN_PERMANENTLY
                        type == TRANSFER_BORROW && constraint >= PRIORITY_USER_INITIATED -> CarAudioTransfer.BORROWED_CONSTRAINED
                        (type == TRANSFER_UNBORROW || type == TRANSFER_UNTAKE) && wasBorrowed && resource.owner == Entity.CONTROLLER ->
                            CarAudioTransfer.UNBORROWED_TO_PHONE
                        else -> null
                    }
                if (transfer != null &&
                    audioTransfers.lastOrNull() != transfer &&
                    audioTransfers.size < 32
                ) {
                    audioTransfers.addLast(transfer)
                }
            }
        }
        return describe()
    }

    companion object {
        const val SCREEN = 1L
        const val MAIN_AUDIO = 2L
        const val TRANSFER_TAKE = 1
        const val TRANSFER_UNTAKE = 2
        const val TRANSFER_BORROW = 3
        const val TRANSFER_UNBORROW = 4
        const val PRIORITY_NICE_TO_HAVE = 100
        const val PRIORITY_USER_INITIATED = 500
        const val CONSTRAINT_ANYTIME = 100
        const val CONSTRAINT_NEVER = 1000

        private fun allows(
            priority: Int,
            constraint: Int,
        ): Boolean =
            constraint == CONSTRAINT_ANYTIME ||
                (constraint == PRIORITY_USER_INITIATED && priority == PRIORITY_USER_INITIATED)
    }
}
