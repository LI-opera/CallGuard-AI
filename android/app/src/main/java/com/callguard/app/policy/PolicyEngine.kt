package com.callguard.app.policy

import com.callguard.app.risk.RiskFeatureExtractor
import com.callguard.app.risk.RiskScoreCalculator

/** Pure local decisions. No Android, database, network, system response or side effects. */
class PolicyEngine(
    private val extractor: RiskFeatureExtractor = RiskFeatureExtractor(),
    private val calculator: RiskScoreCalculator = RiskScoreCalculator(),
) {
    fun decide(input: PolicyInput): PolicyResult {
        fun direct(decision: Decision, reason: String) = PolicyResult(decision, null, listOf(reason))
        if (input.isEmergencyContact) return direct(Decision.ALLOW, "EMERGENCY_CONTACT")
        if (input.isContact) return direct(Decision.ALLOW, "CONTACT")
        if (input.allowRule) return direct(Decision.ALLOW, "ALLOW_RULE")
        if (input.blockRule) return direct(Decision.BLOCK, "BLOCK_RULE")
        if (input.userConfirmedScam) return direct(Decision.BLOCK, "USER_CONFIRMED_SCAM")
        if (input.temporaryAllow?.isActive(input.nowWallTimeMs) == true)
            return direct(Decision.ALLOW, "TEMP_ALLOW")
        val snapshot = input.snapshot ?: return PolicyResult(
            input.protectionLevel.unknownDecision(), null, listOf("LOCAL_DATA_UNAVAILABLE"), fallbackUsed = true,
        )
        val risk = calculator.calculate(extractor.extract(snapshot, input.nowWallTimeMs, input.isContact))
        val decision = input.protectionLevel.fromScore(risk.value, risk.localEvidence.size)
        val reasons = risk.contributions.keys.toMutableList()
        if (risk.value >= 70 && risk.localEvidence.size < 2 && input.protectionLevel != ProtectionLevel.LENIENT)
            reasons += "INSUFFICIENT_LOCAL_EVIDENCE"
        reasons += "POLICY_${input.protectionLevel.name}"
        return PolicyResult(decision, risk.value, reasons, risk.localEvidence)
    }
}
