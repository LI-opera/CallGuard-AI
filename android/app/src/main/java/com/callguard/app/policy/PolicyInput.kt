package com.callguard.app.policy

import com.callguard.app.risk.RiskSnapshot

/** Trusted local facts only. A null snapshot means history/profile lookup is unavailable. */
data class PolicyInput(
    val nowWallTimeMs: Long,
    val protectionLevel: ProtectionLevel = ProtectionLevel.STANDARD,
    val isEmergencyContact: Boolean = false,
    val isContact: Boolean = false,
    val allowRule: Boolean = false,
    val blockRule: Boolean = false,
    val userConfirmedScam: Boolean = false,
    val temporaryAllow: TemporaryAllow? = null,
    val snapshot: RiskSnapshot? = null,
) {
    init { require(nowWallTimeMs >= 0) }
}

data class TemporaryAllow(val createdAtMs: Long, val expiresAtMs: Long, val enabled: Boolean = true) {
    init { require(createdAtMs >= 0 && expiresAtMs > createdAtMs) }
    fun isActive(nowMs: Long): Boolean = enabled && createdAtMs <= nowMs && nowMs < expiresAtMs
}
