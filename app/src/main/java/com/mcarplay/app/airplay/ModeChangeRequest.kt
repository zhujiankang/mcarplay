package com.mcarplay.app.airplay

/** Live changeModes requests, distinct from the permissive /info initial snapshot parser. */
internal object ModeChangeRequest {
    fun valid(params: Map<*, *>?): Boolean {
        if (params == null) return false
        for (key in listOf("appStates", "resources")) {
            if (params.containsKey(key) && params[key] !is List<*>) return false
        }
        for (raw in params["appStates"] as? List<*> ?: emptyList<Any>()) {
            val item = raw as? Map<*, *> ?: return false
            when (integer(item["appStateID"])) {
                1L -> if (integer(item["speechMode"]) !in setOf(-1L, 1L, 2L)) return false
                2L, 3L -> if (item["state"] !is Boolean) return false
                else -> return false
            }
        }
        for (raw in params["resources"] as? List<*> ?: emptyList<Any>()) {
            val item = raw as? Map<*, *> ?: return false
            if (integer(item["resourceID"]) !in setOf(1L, 2L)) return false
            if (integer(item["transferType"]) !in 1L..4L) return false
            // Retain existing receiver compatibility: optional fields may be absent and release
            // requests may repeat valid constraint/priority fields. Never truncate invalid numbers.
            if (item.containsKey("transferPriority") && integer(item["transferPriority"]) !in setOf(100L, 500L)) return false
            for (field in listOf("takeConstraint", "borrowConstraint", "unborrowConstraint")) {
                // Unknown constraints remain restrictive in ModeArbiter.allows. Rejecting a car
                // takeover solely for e.g. 501 could leave the phone using a resource it lost.
                if (item.containsKey(field) && integer(item[field]) !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) return false
            }
            val borrowId = item["borrowID"]
            if (borrowId != null && borrowId !is String && integer(borrowId) == null) return false
            // permanentEntity is a snapshot field, not a live ownership change without a transfer.
            if (item.containsKey("permanentEntity") && integer(item["permanentEntity"]) !in setOf(1L, 2L)) return false
        }
        return true
    }

    private fun integer(value: Any?): Long? =
        when (value) {
            is Byte, is Short, is Int, is Long -> (value as Number).toLong()
            else -> null
        }
}
