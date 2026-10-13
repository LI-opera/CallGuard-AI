package com.callguard.app.risk

import com.callguard.app.policy.*
import org.junit.Assert.*
import org.junit.Test

class RiskFeatureExtractorTest {
    private val extractor=RiskFeatureExtractor()
    @Test fun excludesFutureAndDayBoundaryAndDeduplicatesEventIds() {
        val now=RiskFeatureExtractor.DAY_MS+1000
        val snapshot=RiskSnapshot(previousCalls=listOf(
            HistoricalIncoming("old",1000), HistoricalIncoming("edge",1001),
            HistoricalIncoming("recent",now-1),HistoricalIncoming("recent",now-1),HistoricalIncoming("future",now+1)))
        assertEquals(2,extractor.extract(snapshot,now,false).priorCallsIn24Hours)
    }
    @Test fun repeatUsesProvidedMonotonicAgeNotWallTime() {
        for ((age,expected) in listOf(null to false,0L to true,179999L to true,180000L to true,180001L to false)) {
            val snapshot=RiskSnapshot(previousIncomingAgeMs=age)
            assertEquals(expected,extractor.extract(snapshot,0,false).repeatedWithinThreeMinutes)
            assertEquals(expected,extractor.extract(snapshot,Long.MAX_VALUE,false).repeatedWithinThreeMinutes)
        }
    }
    @Test fun profileThresholdsAreBooleanNotUnboundedWeights() {
        assertFalse(extractor.extract(RiskSnapshot(answeredCount=1),0,false).answeredAtLeastTwice)
        val features=extractor.extract(RiskSnapshot(answeredCount=Long.MAX_VALUE,rejectedCount=Long.MAX_VALUE),0,true)
        assertTrue(features.answeredAtLeastTwice); assertTrue(features.previouslyRejected); assertFalse(features.isStranger)
    }
    @Test fun invalidFactsAreRejectedRatherThanTurnedIntoRisk() {
        assertThrows(IllegalArgumentException::class.java) { RiskSnapshot(rejectedCount=-1) }
        assertThrows(IllegalArgumentException::class.java) { RiskSnapshot(previousIncomingAgeMs=-1) }
        assertThrows(IllegalArgumentException::class.java) { HistoricalIncoming("",0) }
        assertThrows(IllegalArgumentException::class.java) { TemporaryAllow(10,10) }
    }
    @Test fun realExtractorCalculatorEngineChainProducesExplainableDecision() {
        val now=1_000_000L
        val history=(1..3).map { HistoricalIncoming("event-$it",now-it*200000L) }
        val input=PolicyInput(now,snapshot=RiskSnapshot(previousCalls=history,userLabel=UserLabel.MARKETING))
        val result=PolicyEngine().decide(input)
        assertEquals(71,result.riskScore);assertEquals(Decision.BLOCK,result.decision)
        assertEquals(setOf("RECENT_FREQUENCY","USER_MARKETING"),result.localEvidence)
        assertFalse(result.fallbackUsed)
        val repeat=PolicyEngine().decide(input.copy(snapshot=input.snapshot!!.copy(previousIncomingAgeMs=100)))
        assertEquals(63,repeat.riskScore);assertEquals(Decision.SILENCE,repeat.decision)
        assertTrue(repeat.matchedRules.contains("REPEAT_CALL"))
        assertEquals(Decision.BLOCK,PolicyEngine().decide(input.copy(blockRule=true)).decision)
    }
}
