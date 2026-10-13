package com.callguard.app.policy

enum class Decision { ALLOW, SILENCE, BLOCK }

data class PolicyResult(
    val decision: Decision,
    val riskScore: Int?,
    val matchedRules: List<String>,
    val localEvidence: Set<String> = emptySet(),
    val fallbackUsed: Boolean = false,
    val policyVersion: String = "wp03-local-v1",
)
