package com.callguard.app.policy

enum class ProtectionLevel {
    LENIENT, STANDARD, STRICT;

    internal fun fromScore(score: Int, evidenceCount: Int): Decision {
        require(score in 0..100 && evidenceCount >= 0)
        if (this == LENIENT) return if (score < 70) Decision.ALLOW else Decision.SILENCE
        return when {
            score < 40 -> Decision.ALLOW
            score < 70 -> Decision.SILENCE
            evidenceCount >= 2 -> Decision.BLOCK
            else -> Decision.SILENCE
        }
    }

    internal fun unknownDecision(): Decision =
        if (this == STRICT) Decision.SILENCE else Decision.ALLOW
}
