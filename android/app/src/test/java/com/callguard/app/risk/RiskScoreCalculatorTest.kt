package com.callguard.app.risk

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class RiskScoreCalculatorTest(private val name:String, private val features:RiskFeatures, private val expected:Int, private val evidence:Set<String>) {
    @Test fun calculatesSpecifiedWeightsAndDistinctEvidence() {
        val result=RiskScoreCalculator().calculate(features)
        assertEquals(expected,result.value)
        assertEquals(evidence,result.localEvidence)
        assertEquals(result.contributions.values.sum().coerceIn(0,100), result.value)
    }
    companion object {
        @JvmStatic @Parameterized.Parameters(name="{0}") fun cases():List<Array<Any>> {
            val base=RiskFeatures(true,0,false,false,false,false,UserLabel.NONE)
            return listOf(
                arrayOf("first stranger",base,20,emptySet<String>()),
                arrayOf("known number base",base.copy(isStranger=false),10,emptySet<String>()),
                arrayOf("one extra call",base.copy(priorCallsIn24Hours=1),22,setOf("RECENT_FREQUENCY")),
                arrayOf("frequency cap overflow safe",base.copy(priorCallsIn24Hours=Int.MAX_VALUE),40,setOf("RECENT_FREQUENCY")),
                arrayOf("repeat discount",base.copy(repeatedWithinThreeMinutes=true),12,emptySet<String>()),
                arrayOf("callback clamps zero",base.copy(userCalledBack=true),0,emptySet<String>()),
                arrayOf("answered twice",base.copy(answeredAtLeastTwice=true),5,emptySet<String>()),
                arrayOf("rejected once",base.copy(previouslyRejected=true),25,setOf("PREVIOUS_REJECTION")),
                arrayOf("marketing",base.copy(userLabel=UserLabel.MARKETING),65,setOf("USER_MARKETING")),
                arrayOf("family",base.copy(userLabel=UserLabel.FAMILY),0,emptySet<String>()),
                arrayOf("all positive weights",base.copy(priorCallsIn24Hours=10,previouslyRejected=true,userLabel=UserLabel.MARKETING),90,setOf("RECENT_FREQUENCY","PREVIOUS_REJECTION","USER_MARKETING")),
                arrayOf("positive and negative combined",base.copy(priorCallsIn24Hours=10,repeatedWithinThreeMinutes=true,userCalledBack=true,answeredAtLeastTwice=true,previouslyRejected=true,userLabel=UserLabel.MARKETING),42,setOf("RECENT_FREQUENCY","PREVIOUS_REJECTION","USER_MARKETING")),
            )
        }
    }
}
