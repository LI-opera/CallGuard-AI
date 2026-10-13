package com.callguard.app.ai
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.RuntimeEnvironment
import org.json.JSONObject
@RunWith(RobolectricTestRunner::class) @Config(sdk=[35])
class AiExplanationClientTest {
    private val id="11111111-1111-4111-8111-111111111111"
    private fun result()=JSONObject().put("request_id",id).put("call_id",id).put("provider","deepseek").put("model","test")
        .put("prompt_version","wp08-features-v1").put("policy_version","wp03-local-v1").put("feature_digest","a".repeat(64))
        .put("feature_summary",org.json.JSONArray()).put("status","READY").put("error_code",JSONObject.NULL)
        .put("explanation",JSONObject().put("intent","marketing").put("summaryCode","REPEATED_CALLS").put("confidence",0.6).put("riskAdjustment",0))
    @Test fun lowConfidenceShowsOnlyUncertain() {
        val value=AiExplanationClient.validate(result(),id,id)
        assertEquals("无法确定",AiExplanationClient.display(value))
    }
    @Test fun actionExtraFieldAndOutOfBoundsAreRejected() {
        for(key in listOf("decision","riskAdjustment","confidence")) {
            val value=result();val e=value.getJSONObject("explanation")
            when(key) {"decision"->e.put(key,"BLOCK");"riskAdjustment"->e.put(key,11);else->e.put(key,2)}
            assertThrows(IllegalArgumentException::class.java) {AiExplanationClient.validate(value,id,id)}
        }
    }
    @Test fun responseIdentityMustMatchRecord() {
        assertThrows(IllegalArgumentException::class.java) {AiExplanationClient.validate(result(),id,"wrong")}
    }
    @Test fun separateAtomicNotePersistsWithoutRecordMutation() {
        val store=AiExplanationStore(RuntimeEnvironment.getApplication());store.save(id,result())
        assertEquals("READY",store.read(id)!!.getString("status"))
        assertThrows(IllegalArgumentException::class.java) {store.save("../bad",result())}
    }
    @Test fun aiDefaultsOffAndTurningOffPersists() {
        val settings=AiSettings(RuntimeEnvironment.getApplication());settings.enabled=false
        assertFalse(AiSettings(RuntimeEnvironment.getApplication()).enabled)
    }
}
