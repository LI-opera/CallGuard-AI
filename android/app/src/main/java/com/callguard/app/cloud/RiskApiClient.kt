package com.callguard.app.cloud

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicReference

/** No endpoint/key is bundled or enabled by default. Only a caller-supplied HTTPS endpoint
 * (or explicit debug loopback) may receive already tokenized features. */
data class RiskAdvice(val adjustment:Int,val confidence:Double,val intent:String)
data class RiskAttempt(val advice:RiskAdvice?=null,val error:String?=null)
class RiskApiClient(private val endpoint:URL,private val apiKey:String,allowDebugLoopback:Boolean=false):AutoCloseable {
    init {
        require(apiKey.length>=32)
        require(endpoint.userInfo==null && endpoint.query==null && endpoint.ref==null)
        require(endpoint.protocol=="https" || (allowDebugLoopback && endpoint.protocol=="http" && endpoint.host in setOf("127.0.0.1","localhost")))
    }
    private val executor=ThreadPoolExecutor(0,2,10,TimeUnit.SECONDS,SynchronousQueue(),{ r->Thread(r,"risk-http").apply {isDaemon=true} })
    fun analyze(payload:JSONObject,budgetMs:Long):RiskAttempt {
        if(budgetMs<=0) return RiskAttempt(error="CLOUD_TIMEOUT")
        val budget=budgetMs.coerceAtMost(800)
        val deadline=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(budget)
        val connection=AtomicReference<HttpURLConnection?>()
        val future=try { executor.submit(Callable { exchange(payload,deadline,connection) }) }
            catch(_:RejectedExecutionException) { return RiskAttempt(error="CLOUD_BUSY") }
        return try {
            val remaining=deadline-System.nanoTime()
            if(remaining<=0) throw TimeoutException()
            val result=future.get(remaining,TimeUnit.NANOSECONDS)
            if(System.nanoTime()>=deadline) RiskAttempt(error="CLOUD_TIMEOUT") else result
        }
        catch(_:TimeoutException) { future.cancel(true);connection.get()?.disconnect();RiskAttempt(error="CLOUD_TIMEOUT") }
        catch(_:InterruptedException) { future.cancel(true);connection.get()?.disconnect();Thread.currentThread().interrupt();RiskAttempt(error="CLOUD_TIMEOUT") }
        catch(_:Exception) { future.cancel(true);connection.get()?.disconnect();RiskAttempt(error="CLOUD_UNAVAILABLE") }
    }
    private fun exchange(payload:JSONObject,end:Long,active:AtomicReference<HttpURLConnection?>):RiskAttempt {
        val bytes=payload.toString().toByteArray(Charsets.UTF_8)
        for(attempt in 0..1) {
            val remaining=TimeUnit.NANOSECONDS.toMillis(end-System.nanoTime())
            if(remaining<=0 || Thread.currentThread().isInterrupted) return RiskAttempt(error="CLOUD_TIMEOUT")
            val connection=endpoint.openConnection() as HttpURLConnection
            active.set(connection)
            try {
                connection.requestMethod="POST";connection.instanceFollowRedirects=false
                connection.connectTimeout=remaining.coerceAtMost(400).toInt().coerceAtLeast(1)
                connection.readTimeout=remaining.coerceAtMost(400).toInt().coerceAtLeast(1)
                connection.setRequestProperty("Authorization","Bearer $apiKey")
                connection.setRequestProperty("Content-Type","application/json")
                connection.doOutput=true;connection.setFixedLengthStreamingMode(bytes.size)
                connection.outputStream.use { it.write(bytes) }
                val status=connection.responseCode
                if(status!=200) {
                    if(attempt==0 && status in setOf(429,500,502,503,504)) continue
                    return RiskAttempt(error="CLOUD_HTTP_ERROR")
                }
                val body=connection.inputStream.use { input ->
                    val out=java.io.ByteArrayOutputStream();val buf=ByteArray(2048)
                    while(true) { val n=input.read(buf);if(n<0) break;out.write(buf,0,n);require(out.size()<=16384) }
                    out.toString("UTF-8")
                }
                return try { RiskAttempt(advice=parse(body,payload.getString("request_id"))) }
                catch(_:Exception) { RiskAttempt(error="CLOUD_INVALID_RESPONSE") }
            } catch(_:java.io.IOException) {
                if(attempt==1) return RiskAttempt(error="CLOUD_UNAVAILABLE")
            } finally { connection.disconnect();active.compareAndSet(connection,null) }
        }
        return RiskAttempt(error="CLOUD_UNAVAILABLE")
    }
    internal fun parse(body:String,id:String):RiskAdvice {
        val json=JSONObject(body)
        require(json.keys().asSequence().toSet()==setOf("request_id","schema_version","risk_adjustment","intent","confidence","explanation_code","expires_at"))
        require(json.get("request_id")==id && json.get("schema_version")=="risk-001")
        val adjustment=json.get("risk_adjustment")
        require(adjustment is Int && adjustment in -10..10)
        val confidence=json.get("confidence")
        require(confidence is Number && confidence.toDouble().isFinite() && confidence.toDouble() in 0.0..1.0)
        require(json.get("intent") in setOf("unknown","marketing","delivery","normal"))
        require(json.get("explanation_code") in setOf("NO_CLOUD_EVIDENCE","REPEATED_UNANSWERED","LOCAL_FEATURES_ONLY"))
        val expires=Instant.parse(json.getString("expires_at"))
        require(expires>Instant.now() && expires<=Instant.now().plusSeconds(61))
        return RiskAdvice(adjustment,confidence.toDouble(),json.getString("intent"))
    }
    override fun close() {executor.shutdownNow()}
}
