package com.callguard.app.screening
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.callguard.app.cloud.RiskApiClient
import org.junit.Test
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.runner.RunWith
import org.json.JSONObject
import org.json.JSONArray
import java.net.URL
import java.util.UUID
import java.io.File

/** Explicit synthetic loopback probe only. No production phone number, record, or token is read. */
@RunWith(AndroidJUnit4::class)
class RiskApiDeviceTest {
    @Test fun realServerNeutralResponse() {
        val args=InstrumentationRegistry.getArguments()
        val key=args.getString("riskApiKey")
        assumeTrue("Requires explicit local Risk API key and adb reverse",key!=null)
        val id=UUID.randomUUID().toString()
        val payload=JSONObject().put("request_id",id).put("number_token","n1:"+"a".repeat(64))
            .put("features",JSONArray().put(JSONObject().put("code","LOCAL_SCORE").put("value",20)))
            .put("policy_version","wp03-local-v1").put("deadline_ms",800)
        val start=android.os.SystemClock.elapsedRealtime()
        val client=RiskApiClient(URL("http://127.0.0.1:8000/v1/risk/analyze"),key!!,true)
        try {
            val result=client.analyze(payload,800)
            assertNull(result.error);assertNotNull(result.advice);assertEquals(0,result.advice!!.adjustment)
            assertEquals("unknown",result.advice.intent)
            val elapsed=android.os.SystemClock.elapsedRealtime()-start
            File(InstrumentationRegistry.getInstrumentation().targetContext.noBackupFilesDir,"wp07-risk-http.json")
                .writeText(JSONObject().put("requestId",id).put("syntheticInput",true).put("simCallTest",false)
                    .put("adjustment",0).put("intent","unknown").put("elapsedMs",elapsed).toString())
        } finally { client.close() }
    }
}
