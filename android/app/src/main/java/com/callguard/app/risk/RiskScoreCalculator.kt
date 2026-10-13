package com.callguard.app.risk

data class RiskScore(val value: Int, val contributions: Map<String, Int>, val localEvidence: Set<String>)

/** Deterministic baseline, not a trained fraud classifier or probability. */
class RiskScoreCalculator {
    fun calculate(features: RiskFeatures): RiskScore {
        val terms = linkedMapOf("BASE" to 10)
        val evidence = linkedSetOf<String>()
        if (features.isStranger) terms["STRANGER"] = 10
        if (features.priorCallsIn24Hours > 0) {
            terms["RECENT_FREQUENCY"] = features.priorCallsIn24Hours.coerceAtMost(10) * 2
            evidence += "RECENT_FREQUENCY"
        }
        if (features.repeatedWithinThreeMinutes) terms["REPEAT_CALL"] = -8
        if (features.userCalledBack) terms["USER_CALLED_BACK"] = -25
        if (features.answeredAtLeastTwice) terms["ANSWERED_TWICE"] = -15
        if (features.previouslyRejected) {
            terms["PREVIOUS_REJECTION"] = 5
            evidence += "PREVIOUS_REJECTION"
        }
        when (features.userLabel) {
            UserLabel.MARKETING -> { terms["USER_MARKETING"] = 45; evidence += "USER_MARKETING" }
            UserLabel.HARASSMENT -> { terms["USER_HARASSMENT"] = 45; evidence += "USER_HARASSMENT" }
            UserLabel.FAMILY -> terms["USER_FAMILY"] = -60
            UserLabel.NONE -> Unit
        }
        // Baseline/stranger status are not independent risk evidence. Repeated counts are one category.
        return RiskScore(terms.values.sum().coerceIn(0, 100), terms.toMap(), evidence.toSet())
    }
}
