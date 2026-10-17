package org.fossify.messages.forwarding

/** UNKNOWN always stops: a timeout is not proof that the previous target received nothing. */
internal object GroupExecutionPolicy {
    const val ALL = "all"
    const val UNTIL_SUCCESS = "until_success"
    const val UNTIL_FAIL = "until_fail"
    fun shouldContinue(mode: String, status: String): Boolean = when (mode) {
        ALL -> status in setOf("success", "failed", "skipped")
        UNTIL_SUCCESS -> status in setOf("failed", "skipped")
        UNTIL_FAIL -> status == "success"
        else -> false
    }
}
