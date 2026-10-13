package com.callguard.app.cloud
import com.callguard.app.policy.*
import com.callguard.app.screening.*
import org.json.JSONArray
import org.json.JSONObject

fun interface CloudRiskGateway {
    fun refine(request:ScreeningRequest,state:ScreeningState,local:PolicyResult,budgetMs:Long):PolicyResult
}
class HttpCloudRiskGateway(private val client:RiskApiClient):CloudRiskGateway {
    override fun refine(request:ScreeningRequest,state:ScreeningState,local:PolicyResult,budgetMs:Long):PolicyResult {
        // Never upload contacts/unknown identities or override direct local rules.
        val token=state.number?.numberToken ?: return local
        if(local.riskScore==null || !token.matches(Regex("n1:[0-9a-f]{64}"))) return local
        val features=JSONArray().put(JSONObject().put("code","LOCAL_SCORE").put("value",local.riskScore))
        val payload=JSONObject().put("request_id",request.eventId).put("number_token",token)
            .put("features",features).put("policy_version",local.policyVersion).put("deadline_ms",budgetMs.coerceIn(1,800))
        val result=client.analyze(payload,budgetMs)
        val advice=result.advice ?: return local.copy(fallbackUsed=true,matchedRules=local.matchedRules+requireNotNull(result.error))
        if(advice.confidence<0.7) return local.copy(matchedRules=local.matchedRules+"CLOUD_UNCERTAIN")
        val score=(local.riskScore+advice.adjustment).coerceIn(0,100)
        return local.copy(riskScore=score,decision=state.input.protectionLevel.fromScore(score,local.localEvidence.size),
            matchedRules=local.matchedRules+"CLOUD_ADJUSTMENT")
    }
}
