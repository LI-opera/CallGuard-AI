package com.callguard.app.policy

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class ProtectionLevelTest(private val level: ProtectionLevel, private val score: Int, private val evidence: Int, private val expected: Decision) {
    @Test fun mapsBoundaryWithEvidenceGate() { assertEquals(expected, level.fromScore(score,evidence)) }
    companion object {
        @JvmStatic @Parameterized.Parameters(name="{0}_score{1}_evidence{2}") fun cases(): List<Array<Any>> {
            val rows = mutableListOf<Array<Any>>()
            for (level in ProtectionLevel.entries) {
                val expected = if (level == ProtectionLevel.LENIENT)
                    listOf(Decision.ALLOW,Decision.ALLOW,Decision.ALLOW,Decision.ALLOW,Decision.SILENCE,Decision.SILENCE)
                else listOf(Decision.ALLOW,Decision.ALLOW,Decision.SILENCE,Decision.SILENCE,Decision.BLOCK,Decision.BLOCK)
                listOf(0,39,40,69,70,100).forEachIndexed { i, score -> rows += arrayOf(level,score,2,expected[i]) }
                for (evidence in 0..1) rows += arrayOf(level,100,evidence,Decision.SILENCE)
            }
            return rows
        }
    }
}
