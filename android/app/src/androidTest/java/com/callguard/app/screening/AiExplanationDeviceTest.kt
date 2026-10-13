package com.callguard.app.screening
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.callguard.app.ai.*
import org.junit.Test
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.runner.RunWith
import org.json.JSONObject
import org.json.JSONArray
import java.util.UUID
import java.io.File

/** Explicit experiment on synthetic completed-call features; never reads real call records. */
@RunWith(AndroidJUnit4::class)
class AiExplanationDeviceTest {
    @Test fun realModelExplanationIsSeparateAndCloudCanBeDisabled() {
        val key=InstrumentationRegistry.getArguments().getString("riskApiKey")
        assumeTrue("Requires explicit local backend credentials",key!=null)
        val app=InstrumentationRegistry.getInstrumentation().targetContext
        val settings=AiSettings(app);val wasEnabled=settings.enabled
        val requestId=UUID.randomUUID().toString();val callId=UUID.randomUUID().toString()
        try {
            settings.save("http://127.0.0.1:8000",key!!)
            assertEquals(key,settings.backendKey())
            settings.enabled=true
            val payload=JSONObject().put("request_id",requestId).put("call_id",callId).put("call_ended",true)
                .put("policy_version","wp03-local-v1").put("features",JSONArray().put(JSONObject().put("code","LOCAL_SCORE").put("value",20)))
            val start=android.os.SystemClock.elapsedRealtime()
            val result=AiExplanationClient().explain(settings.endpoint,settings.backendKey(),payload)
            assertEquals("READY",result.getString("status"))
            AiExplanationStore(app).save(callId,result)
            assertEquals(requestId,AiExplanationStore(app).read(callId)!!.getString("request_id"))
            val explanation=result.getJSONObject("explanation")
            if(explanation.getDouble("confidence")<0.7)assertEquals("无法确定",AiExplanationClient.display(result))
            settings.enabled=false;assertFalse(AiSettings(app).enabled)
            File(app.noBackupFilesDir,"wp08-ai-probe.json").writeText(JSONObject()
                .put("syntheticInput",true).put("simCallTest",false).put("elapsedMs",android.os.SystemClock.elapsedRealtime()-start)
                .put("cloudDisabledVerified",true).put("result",result).put("display",AiExplanationClient.display(result)).toString())
        } finally {settings.enabled=wasEnabled}
    }
}
