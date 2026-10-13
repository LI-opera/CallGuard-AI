package com.callguard.app.cloud
import java.net.ServerSocket
import java.net.InetSocketAddress
import java.net.URL
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class) @Config(sdk=[35])
class RiskApiClientTest {
    private lateinit var server:ServerSocket
    private lateinit var workers:java.util.concurrent.ExecutorService
    private lateinit var client:RiskApiClient
    private val count=AtomicInteger()
    private val id="11111111-1111-4111-8111-111111111111"
    private var handler:(Int)->Pair<Int,String> = {200 to response()}
    private var posted=""
    @Before fun setup() {
        server=ServerSocket(0,8,java.net.InetAddress.getByName("127.0.0.1"))
        workers=Executors.newCachedThreadPool()
        workers.execute {
            while(!server.isClosed) {
                val socket=try {server.accept()} catch(_:Exception) {break}
                workers.execute {
                    socket.use {
                        try {
                            val reader=socket.getInputStream().bufferedReader(Charsets.UTF_8)
                            reader.readLine()
                            var length=0
                            while(true) {
                                val header=reader.readLine() ?: break
                                if(header.isEmpty()) break
                                if(header.startsWith("Content-Length:",true)) length=header.substringAfter(':').trim().toInt()
                            }
                            val body=CharArray(length);var read=0
                            while(read<length) {val n=reader.read(body,read,length-read);if(n<0) break;read+=n}
                            posted=String(body)
                            val (status,response)=handler(count.incrementAndGet())
                            val bytes=response.toByteArray(Charsets.UTF_8)
                            val header="HTTP/1.1 $status Test\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
                            socket.getOutputStream().write(header.toByteArray(Charsets.US_ASCII))
                            socket.getOutputStream().write(bytes);socket.getOutputStream().flush()
                        } catch(_:Exception) { }
                    }
                }
            }
        }
        client=RiskApiClient(URL("http://127.0.0.1:${server.localPort}/v1/risk/analyze"),"x".repeat(32),true)
    }
    @After fun close() {client.close();server.close();workers.shutdownNow()}
    private fun payload()=JSONObject().put("request_id",id).put("number_token","n1:"+"a".repeat(64))
    private fun response()=JSONObject().put("request_id",id).put("schema_version","risk-001")
        .put("risk_adjustment",0).put("intent","unknown").put("confidence",0.0)
        .put("explanation_code","NO_CLOUD_EVIDENCE").put("expires_at",Instant.now().plusSeconds(60).toString()).toString()
    @Test fun fastResponseIsParsedFromRealHttp() {
        handler={Thread.sleep(100);200 to response()}
        assertEquals(0,client.analyze(payload(),800).advice!!.adjustment)
        assertEquals(1,count.get());assertTrue(posted.contains(id))
    }
    @Test fun transient500RetriesOnlyOnceAndKeepsIdentity() {
        handler={n->if(n==1) 500 to "{}" else 200 to response()}
        assertNotNull(client.analyze(payload(),800).advice);assertEquals(2,count.get())
        assertEquals(id,JSONObject(posted).getString("request_id"))
    }
    @Test fun repeated429StopsAfterTwoAttempts() {
        handler={429 to "{}"}
        assertEquals("CLOUD_HTTP_ERROR",client.analyze(payload(),800).error);assertEquals(2,count.get())
    }
    @Test fun authFailureNotRetried() {
        handler={401 to "{}"};assertNull(client.analyze(payload(),800).advice);assertEquals(1,count.get())
    }
    @Test fun invalidJsonIsRejectedWithoutRetry() {
        handler={200 to "not-json"}
        assertEquals("CLOUD_INVALID_RESPONSE",client.analyze(payload(),800).error);assertEquals(1,count.get())
    }
    @Test fun slowRequestAndRetryShareSingleBudget() {
        handler={Thread.sleep(1200);200 to response()}
        val start=System.nanoTime();val result=client.analyze(payload(),800)
        val elapsed=(System.nanoTime()-start)/1000000
        assertNull(result.advice);assertTrue("elapsed=$elapsed",elapsed<1000);assertTrue(count.get()<=2)
    }
    @Test fun smallerRemainingBudgetIsRespected() {
        handler={Thread.sleep(800);200 to response()}
        val start=System.nanoTime();assertNull(client.analyze(payload(),80).advice)
        assertTrue((System.nanoTime()-start)/1000000<250)
    }
    @Test fun schemaRejectsExtraFieldBoundsExpiryAndWrongIdentity() {
        val bad=listOf(JSONObject(response()).put("decision","BLOCK"),JSONObject(response()).put("risk_adjustment",11),
            JSONObject(response()).put("confidence",2.0),JSONObject(response()).put("request_id","wrong"),
            JSONObject(response()).put("risk_adjustment","2"),JSONObject(response()).put("expires_at",Instant.now().minusSeconds(1).toString()))
        bad.forEach {body->handler={200 to body.toString()};assertNull(client.analyze(payload(),800).advice)}
    }
    private fun state()=com.callguard.app.screening.ScreeningState(
        com.callguard.app.policy.PolicyInput(100),
        com.callguard.app.caller.NormalizedNumber("n1:"+"a".repeat(64),com.callguard.app.caller.NormalizationStatus.NORMALIZED,null,null))
    private fun request()=com.callguard.app.screening.ScreeningRequest(id,null,true,100,com.callguard.app.screening.ScreeningDeadline(0))
    @Test fun httpErrorsRetainExactLocalSilenceScoreAndRules() {
        handler={500 to "{}"}
        val local=com.callguard.app.policy.PolicyResult(com.callguard.app.policy.Decision.SILENCE,65,listOf("USER_HARASSMENT"))
        val result=HttpCloudRiskGateway(client).refine(request(),state(),local,800)
        assertEquals(local.decision,result.decision);assertEquals(local.riskScore,result.riskScore)
        assertTrue(result.fallbackUsed);assertTrue("USER_HARASSMENT" in result.matchedRules)
    }
    @Test fun cloudCannotOverrideDirectRulesOrSupplyMissingLocalEvidence() {
        val direct=com.callguard.app.policy.PolicyResult(com.callguard.app.policy.Decision.ALLOW,null,listOf("ALLOW_RULE"))
        assertEquals(direct,HttpCloudRiskGateway(client).refine(request(),state(),direct,800));assertEquals(0,count.get())
        handler={200 to JSONObject(response()).put("risk_adjustment",10).put("confidence",0.9).toString()}
        val local=com.callguard.app.policy.PolicyResult(com.callguard.app.policy.Decision.SILENCE,65,listOf("USER_HARASSMENT"),setOf("USER_HARASSMENT"))
        val result=HttpCloudRiskGateway(client).refine(request(),state(),local,800)
        assertEquals(75,result.riskScore);assertEquals(com.callguard.app.policy.Decision.SILENCE,result.decision)
        assertEquals(local.localEvidence,result.localEvidence)
    }
    @Test fun lowConfidenceKeepsLocalScore() {
        handler={200 to JSONObject(response()).put("risk_adjustment",10).put("confidence",0.5).toString()}
        val local=com.callguard.app.policy.PolicyResult(com.callguard.app.policy.Decision.ALLOW,20,listOf("STRANGER"))
        val result=HttpCloudRiskGateway(client).refine(request(),state(),local,800)
        assertEquals(20,result.riskScore);assertEquals(local.decision,result.decision);assertTrue("CLOUD_UNCERTAIN" in result.matchedRules)
    }
    @Test fun productionConfigurationRequiresHttps() {
        assertThrows(IllegalArgumentException::class.java) {RiskApiClient(URL("http://example.com/v1/risk/analyze"),"x".repeat(32))}
    }
}
