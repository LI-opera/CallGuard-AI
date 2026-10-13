package com.callguard.app.ai
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONObject

class AiExplanationClient {
    private val active=AtomicReference<HttpURLConnection?>()
    fun cancel() {active.get()?.disconnect()}
    fun explain(endpoint:String,key:String,payload:JSONObject):JSONObject {
        AiSettings.validateEndpoint(endpoint)
        val connection=URL(endpoint+"/v1/explanations").openConnection() as HttpURLConnection
        active.set(connection)
        try {
            connection.requestMethod="POST";connection.instanceFollowRedirects=false
            connection.connectTimeout=5000;connection.readTimeout=30000;connection.doOutput=true
            connection.setRequestProperty("Authorization","Bearer $key")
            connection.setRequestProperty("Content-Type","application/json")
            val bytes=payload.toString().toByteArray(Charsets.UTF_8);connection.setFixedLengthStreamingMode(bytes.size)
            connection.outputStream.use {it.write(bytes)}
            check(connection.responseCode==200) {"AI request unavailable"}
            val body=connection.inputStream.use {input ->
                val out=java.io.ByteArrayOutputStream();val buffer=ByteArray(2048)
                while(true) {val n=input.read(buffer);if(n<0)break;out.write(buffer,0,n);require(out.size()<=32768)}
                out.toString("UTF-8")
            }
            return validate(JSONObject(body),payload.getString("request_id"),payload.getString("call_id"))
        } finally {connection.disconnect();active.compareAndSet(connection,null)}
    }
    companion object {
        fun validate(value:JSONObject,requestId:String,callId:String):JSONObject {
            require(value.keys().asSequence().toSet()==setOf("request_id","call_id","provider","model","prompt_version","policy_version","feature_digest","feature_summary","status","explanation","error_code"))
            require(value.get("request_id")==requestId && value.get("call_id")==callId)
            require(value.get("policy_version")=="wp03-local-v1" && value.get("prompt_version")=="wp08-features-v1")
            for(key in listOf("provider","model","feature_digest")) require(value.get(key) is String && value.getString(key).length in 1..128)
            require(value.get("status") in setOf("PENDING","READY","FAILED"))
            if(value.getString("status")=="READY") {
                val e=value.getJSONObject("explanation")
                require(e.keys().asSequence().toSet()==setOf("intent","summaryCode","confidence","riskAdjustment"))
                require(e.get("intent") in setOf("unknown","marketing","delivery","normal"))
                require(e.get("summaryCode") in setOf("INSUFFICIENT_EVIDENCE","REPEATED_CALLS","USER_MARKED_HARASSMENT","LOW_LOCAL_RISK","HIGH_LOCAL_RISK"))
                val confidence=e.get("confidence");val adjustment=e.get("riskAdjustment")
                require(confidence is Number && confidence.toDouble().isFinite() && confidence.toDouble() in 0.0..1.0)
                require(adjustment is Int && adjustment in -10..10)
            } else require(value.isNull("explanation"))
            return value
        }
        fun display(value:JSONObject):String {
            if(value.optString("status")!="READY")return if(value.optString("status")=="PENDING") "解释尚未完成，请稍后查询" else "解释不可用，本地记录保持不变"
            val e=value.getJSONObject("explanation")
            if(e.getDouble("confidence")<0.7 || e.getString("intent")=="unknown")return "无法确定"
            return when(e.getString("summaryCode")) {
                "USER_MARKED_HARASSMENT"->"用户曾标记骚扰；这不是已核实的诈骗结论"
                "REPEATED_CALLS"->"存在短时间重复来电特征"
                "LOW_LOCAL_RISK"->"已提供的本地特征风险较低"
                "HIGH_LOCAL_RISK"->"已提供的本地特征风险较高"
                else->"无法确定"
            }
        }
    }
}
