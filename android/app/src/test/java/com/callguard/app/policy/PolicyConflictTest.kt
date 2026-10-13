package com.callguard.app.policy

import com.callguard.app.risk.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** Cloud-labelled rows verify only the local boundary; no cloud client or timeout is implemented. */
@RunWith(Parameterized::class)
class PolicyConflictTest(
    private val name: String,
    private val input: PolicyInput,
    private val expected: Decision,
    private val expectedReason: String,
    private val fallback: Boolean,
) {
    @Test fun resolvesConflictUsingTrustedLocalPriority() {
        val result = PolicyEngine().decide(input)
        assertEquals(expected, result.decision)
        assertTrue(result.matchedRules.contains(expectedReason))
        assertEquals(fallback, result.fallbackUsed)
        assertEquals("wp03-local-v1", result.policyVersion)
    }

    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}") fun cases(): List<Array<Any>> {
            val now = 1_000_000L
            val ordinary = PolicyInput(now, snapshot = RiskSnapshot())
            return listOf(
                arrayOf("allow beats block", ordinary.copy(allowRule=true, blockRule=true), Decision.ALLOW, "ALLOW_RULE", false),
                arrayOf("block beats repeat", ordinary.copy(blockRule=true, snapshot=RiskSnapshot(previousIncomingAgeMs=1)), Decision.BLOCK, "BLOCK_RULE", false),
                arrayOf("contact cannot be overridden by unconsumed cloud high-risk", ordinary.copy(isContact=true, blockRule=true), Decision.ALLOW, "CONTACT", false),
                arrayOf("expired temp", ordinary.copy(temporaryAllow=TemporaryAllow(0,now), snapshot=RiskSnapshot(userLabel=UserLabel.MARKETING)), Decision.SILENCE, "USER_MARKETING", false),
                arrayOf("cloud timeout unavailable to local-only engine", ordinary, Decision.ALLOW, "POLICY_STANDARD", false),
                arrayOf("database lookup failed", ordinary.copy(snapshot=null), Decision.ALLOW, "LOCAL_DATA_UNAVAILABLE", true),
                arrayOf("strict database failure", ordinary.copy(snapshot=null, protectionLevel=ProtectionLevel.STRICT), Decision.SILENCE, "LOCAL_DATA_UNAVAILABLE", true),
                arrayOf("lenient database failure", ordinary.copy(snapshot=null, protectionLevel=ProtectionLevel.LENIENT), Decision.ALLOW, "LOCAL_DATA_UNAVAILABLE", true),
                arrayOf("known block survives lookup failure", ordinary.copy(blockRule=true,snapshot=null), Decision.BLOCK, "BLOCK_RULE", false),
                arrayOf("known allow survives lookup failure", ordinary.copy(allowRule=true,snapshot=null), Decision.ALLOW, "ALLOW_RULE", false),
                arrayOf("emergency beats scam", ordinary.copy(isEmergencyContact=true,userConfirmedScam=true), Decision.ALLOW, "EMERGENCY_CONTACT", false),
                arrayOf("scam beats temporary allow", ordinary.copy(userConfirmedScam=true,temporaryAllow=TemporaryAllow(0,now+1)), Decision.BLOCK, "USER_CONFIRMED_SCAM", false),
                arrayOf("active temporary allow", ordinary.copy(temporaryAllow=TemporaryAllow(now,now+1),snapshot=null), Decision.ALLOW, "TEMP_ALLOW", false),
                arrayOf("future temporary allow", ordinary.copy(temporaryAllow=TemporaryAllow(now+1,now+2)), Decision.ALLOW, "POLICY_STANDARD", false),
                arrayOf("disabled temporary allow", ordinary.copy(temporaryAllow=TemporaryAllow(0,now+1,false)), Decision.ALLOW, "POLICY_STANDARD", false),
                arrayOf("family label is scored not whitelist", ordinary.copy(blockRule=true,snapshot=RiskSnapshot(userLabel=UserLabel.FAMILY)), Decision.BLOCK, "BLOCK_RULE", false),
            )
        }
    }
}
