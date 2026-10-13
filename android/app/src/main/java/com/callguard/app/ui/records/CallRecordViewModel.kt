package com.callguard.app.ui.records
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.compose.runtime.*
import com.callguard.app.data.*
import com.callguard.app.data.dao.DaySummary
import com.callguard.app.data.entity.*
import com.callguard.app.feedback.*
import com.callguard.app.platform.AndroidWindowClock
import com.callguard.app.ui.home.HomeViewModel
import kotlinx.coroutines.*

class CallRecordViewModel(app:Application):AndroidViewModel(app) {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    var rows by mutableStateOf<List<CallRecordEntity>>(emptyList()); private set
    var summary by mutableStateOf(DaySummary()); private set
    var selected by mutableStateOf<CallRecordEntity?>(null); private set
    var profile by mutableStateOf<CallerProfileEntity?>(null); private set
    var lastFeedback by mutableStateOf<FeedbackEventEntity?>(null); private set
    var rules by mutableStateOf<List<RuleEntryEntity>>(emptyList()); private set
    var busy by mutableStateOf(false); private set
    var more by mutableStateOf(true); private set
    var message by mutableStateOf(""); private set
    val aiSettings=com.callguard.app.ai.AiSettings(app)
    var aiEnabled by mutableStateOf(aiSettings.enabled); private set
    var aiConfigured by mutableStateOf(aiSettings.configured); private set
    var aiBusy by mutableStateOf(false); private set
    var aiMessage by mutableStateOf(""); private set
    var explanation by mutableStateOf<org.json.JSONObject?>(null); private set
    private val aiClient=com.callguard.app.ai.AiExplanationClient()
    private var aiGeneration=0
    private var aiJob:Job?=null
    private var refreshPending=false
    fun configureAi(endpoint:String,key:String) {
        try {aiSettings.save(endpoint,key);aiConfigured=true;aiMessage="后端配置已保存，模型密钥只保留在服务器。"}
        catch(_:Exception) {aiMessage="配置未保存，请检查服务地址与访问密钥。"}
    }
    fun toggleAi(enabled:Boolean) {
        if(enabled && !aiConfigured) {aiMessage="请先配置后端服务";return}
        aiGeneration++;aiSettings.enabled=enabled;aiEnabled=enabled
        if(!enabled) {aiClient.cancel();aiJob?.cancel();aiBusy=false;aiMessage="云端 AI 已关闭，本地筛查继续工作。"}
    }
    fun explainEndedCall() {
        val record=selected ?: return
        if(!aiEnabled || !aiConfigured || aiBusy) return
        aiBusy=true;val generation=aiGeneration
        aiJob=scope.launch {
            try {
                val response=withContext(Dispatchers.IO) {
                    check(aiSettings.enabled)
                    val store=com.callguard.app.ai.AiExplanationStore(getApplication())
                    val old=store.read(record.id)
                    val requestId=old?.optString("request_id")?.takeIf {it.isNotBlank()} ?: java.util.UUID.randomUUID().toString()
                    val features=org.json.JSONArray()
                    record.riskScore?.let {features.put(org.json.JSONObject().put("code","LOCAL_SCORE").put("value",it))}
                    features.put(org.json.JSONObject().put("code","REPEAT_CALL").put("value",record.repeatCount.coerceAtMost(1000)))
                    val payload=org.json.JSONObject().put("request_id",requestId).put("call_id",record.id).put("call_ended",true)
                        .put("features",features).put("policy_version",record.policyVersion)
                    store.save(record.id,org.json.JSONObject().put("request_id",requestId).put("status","PENDING"))
                    check(aiSettings.enabled)
                    aiClient.explain(aiSettings.endpoint,aiSettings.backendKey(),payload)
                }
                if(aiEnabled && generation==aiGeneration) {
                    withContext(Dispatchers.IO) {com.callguard.app.ai.AiExplanationStore(getApplication()).save(record.id,response)}
                    if(selected?.id==record.id)explanation=response
                    aiMessage="AI 解释已更新；历史动作和本地规则未修改。"
                }
            } catch(e:CancellationException) {throw e}
            catch(_:Exception) {aiMessage="AI 解释未完成，本地筛查不受影响。稍后可使用同一请求查询。"}
            finally {if(generation==aiGeneration)aiBusy=false}
        }
    }
    private suspend fun <T> database(block:(AppDatabase)->T):T=withContext(Dispatchers.IO) {
        val db=AppDatabase.open(getApplication())
        try { block(db) } finally { db.close() }
    }
    private fun operation(block:suspend ()->Unit) {
        if(busy) return
        busy=true
        scope.launch {
            try { block() }
            catch(e:CancellationException) { throw e }
            catch(_:Exception) { message="操作未确认完成。请刷新重试；已保存的反馈会在下次打开或刷新时重试应用。" }
            finally {
                busy=false
                if(refreshPending) { refreshPending=false; refresh() }
            }
        }
    }
    fun refresh() {
        if(busy) { refreshPending=true; return }
        operation {
            database { FeedbackHandler(it).drain() }
            val page=database { it.callRecords().page(null,"",21) }
            rows=page.take(20);more=page.size>20
            summary=database { HomeViewModel().load(it) }
            selected?.let { loadDetail(it.id) }
        }
    }
    fun nextPage()=operation {
        val tail=rows.lastOrNull() ?: return@operation
        val page=database { it.callRecords().page(tail.receivedAt,tail.id,21) }
        rows=rows+page.take(20);more=page.size>20
    }
    private suspend fun loadDetail(id:String) {
        val detail=database { db ->
            val record=requireNotNull(db.callRecords().get(id))
            val time=AndroidWindowClock(getApplication()).now()
            Detail(record,db.callerProfiles().get(record.numberToken),db.feedback().latest(record.numberToken),
                db.rules().activeForClock(record.numberToken,time.elapsedMs,time.bootCount))
        }
        explanation=withContext(Dispatchers.IO) {com.callguard.app.ai.AiExplanationStore(getApplication()).read(id)}
        selected=detail.record;profile=detail.profile;lastFeedback=detail.feedback;rules=detail.rules
    }
    fun open(id:String)=operation { message="";loadDetail(id) }
    fun closeDetail() { if(!busy) { selected=null;message="" } }
    fun feedback(action:FeedbackAction) {
        val record=selected ?: return
        val version=profile?.profileVersion ?: return
        operation {
            database { db ->
                val handler=FeedbackHandler(db)
                handler.enqueue(record.id,action,version)
                handler.drain()
            }
            loadDetail(record.id)
            message="反馈已在本机生效，仅影响后续来电；尚无云端同步。"
        }
    }
    fun temporaryAllow() {
        val record=selected ?: return
        operation {
            database { RuleActionsRepository(it,AndroidWindowClock(getApplication())).allowForFiveMinutes(record.id) }
            loadDetail(record.id)
            message="未来5分钟允许该号码来电；明确黑名单仍优先。"
        }
    }
    override fun onCleared() { aiClient.cancel();scope.cancel() }
    private data class Detail(val record:CallRecordEntity,val profile:CallerProfileEntity?,val feedback:FeedbackEventEntity?,val rules:List<RuleEntryEntity>)
}
