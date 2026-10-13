package com.callguard.app.risk

/** Historical events for ONE normalized number, excluding the current call. No raw phone numbers. */
data class HistoricalIncoming(val eventId: String, val receivedAtMs: Long) {
    init { require(eventId.isNotBlank() && receivedAtMs >= 0) }
}

enum class UserLabel { NONE, MARKETING, FAMILY, HARASSMENT }

data class RiskSnapshot(
    val previousCalls: List<HistoricalIncoming> = emptyList(),
    // Same-boot monotonic age supplied by a future platform adapter; null means unknown.
    val previousIncomingAgeMs: Long? = null,
    val userCalledBack: Boolean = false,
    val answeredCount: Long = 0,
    val rejectedCount: Long = 0,
    val userLabel: UserLabel = UserLabel.NONE,
) {
    init {
        require(answeredCount >= 0 && rejectedCount >= 0)
        require(previousIncomingAgeMs == null || previousIncomingAgeMs >= 0)
    }
}

data class RiskFeatures(
    val isStranger: Boolean,
    val priorCallsIn24Hours: Int,
    val repeatedWithinThreeMinutes: Boolean,
    val userCalledBack: Boolean,
    val answeredAtLeastTwice: Boolean,
    val previouslyRejected: Boolean,
    val userLabel: UserLabel,
) {
    init { require(priorCallsIn24Hours >= 0) }
}

class RiskFeatureExtractor {
    fun extract(snapshot: RiskSnapshot, nowWallTimeMs: Long, isContact: Boolean): RiskFeatures {
        require(nowWallTimeMs >= 0)
        val recent = snapshot.previousCalls
            .filter { it.receivedAtMs <= nowWallTimeMs && nowWallTimeMs - it.receivedAtMs < DAY_MS }
            .distinctBy { it.eventId }
        return RiskFeatures(
            isStranger = !isContact,
            priorCallsIn24Hours = recent.size,
            repeatedWithinThreeMinutes = snapshot.previousIncomingAgeMs?.let { it <= REPEAT_MS } ?: false,
            userCalledBack = snapshot.userCalledBack,
            answeredAtLeastTwice = snapshot.answeredCount >= 2,
            previouslyRejected = snapshot.rejectedCount >= 1,
            userLabel = snapshot.userLabel,
        )
    }

    companion object {
        const val DAY_MS = 86_400_000L
        const val REPEAT_MS = 180_000L
    }
}
