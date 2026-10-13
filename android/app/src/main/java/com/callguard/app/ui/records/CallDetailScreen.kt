package com.callguard.app.ui.records

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import com.callguard.app.data.entity.CallRecordEntity
import com.callguard.app.feedback.FeedbackAction
import org.json.JSONArray
import java.text.DateFormat
import java.util.Date

fun recordTime(record:CallRecordEntity):String=DateFormat.getDateTimeInstance(DateFormat.SHORT,DateFormat.MEDIUM).format(Date(record.receivedAt))
fun actionText(action:String):String=when(action) { "ALLOW"->"已放行";"SILENCE"->"已静音";"BLOCK"->"已拦截";else->"动作未知" }
fun reasonText(raw:String):String {
    val reasons=runCatching { JSONArray(raw).let { a->(0 until a.length()).map { a.getString(it) } } }.getOrDefault(emptyList())
    val names=mapOf("ALLOW_RULE" to "用户白名单", "BLOCK_RULE" to "用户黑名单", "TEMP_ALLOW" to "临时允许生效",
        "CONTACT" to "通讯录联系人", "EMERGENCY_CONTACT" to "紧急联系人", "USER_CONFIRMED_SCAM" to "用户标记诈骗",
        "USER_MARKETING" to "用户标记营销", "USER_HARASSMENT" to "用户标记骚扰", "USER_FAMILY" to "用户标记亲友",
        "REPEAT_CALL" to "短时间重复来电", "RECENT_FREQUENCY" to "近期来电频次", "STRANGER" to "陌生号码",
        "LOCAL_DATA_UNAVAILABLE" to "本地资料不可用", "INSUFFICIENT_LOCAL_EVIDENCE" to "拦截依据不足",
        "USER_CALLED_BACK" to "曾主动回拨", "ANSWERED_TWICE" to "曾多次接听", "PREVIOUS_REJECTION" to "曾拒接")
    val labels=reasons.filterNot { it=="BASE" || it.startsWith("POLICY_") }.map { names[it] ?: "其他本地规则" }.distinct()
    return labels.ifEmpty { listOf("本地策略判断") }.joinToString("、")
}
@Composable fun CallDetailScreen(vm:CallRecordViewModel,simple:Boolean) {
    val record=vm.selected ?: return
    var confirmAi by remember(record.id) {mutableStateOf(false)}
    var confirm by remember(record.id) { mutableStateOf<FeedbackAction?>(null) }
    OutlinedButton(onClick={vm.closeDetail()},enabled=!vm.busy) { Text("返回记录") }
    Text("来电详情",style=MaterialTheme.typography.headlineSmall)
    Text(recordTime(record))
    Text(actionText(record.decision))
    Text("以上为当时提交给系统的动作，不代表已核实实际响铃。")
    Text("号码未保存；意图未分析。按时间和记录编号区分。")
    Text("记录编号：${record.id}")
    Text("当时依据：${reasonText(record.matchedRules)}")
    if(record.fallbackUsed) Text("本次使用了异常降级处理")
    if(!simple) {
        Text("规则分数：${record.riskScore?.toString() ?: "未评分"}；提交耗时：${record.latencyMs?.let { "${it}ms" } ?: "未记录"}")
        Text("策略版本：${record.policyVersion}")
    }
    Text("当前用户标签："+when(vm.profile?.userLabel) { "NORMAL"->"正常电话";"HARASSMENT"->"骚扰电话";"MARKETING"->"营销";"FAMILY"->"亲友";"SCAM"->"用户标记诈骗";else->"未标记" })
    Text("当前名单："+vm.rules.joinToString("、") { when(it.type) { "ALLOW"->"白名单";"BLOCK"->"黑名单";else->"临时允许" } }.ifBlank { "无" })
    vm.lastFeedback?.let { Text(if(it.applied) "最近反馈已本地应用；未同步云端" else "最近反馈已保存，等待本地应用；请刷新重试") }
    if(record.normalizationStatus=="NORMALIZED") {
        Text("正常/骚扰只更新标签，不自动加入名单。名单优先；联系人仍优先放行。")
        Button(onClick={vm.feedback(FeedbackAction.NORMAL)},enabled=!vm.busy) { Text("这是正常电话") }
        Button(onClick={vm.feedback(FeedbackAction.HARASSMENT)},enabled=!vm.busy) { Text("这是骚扰电话") }
        OutlinedButton(onClick={confirm=FeedbackAction.ALLOW},enabled=!vm.busy) { Text("加入白名单") }
        OutlinedButton(onClick={confirm=FeedbackAction.BLOCK},enabled=!vm.busy) { Text("加入黑名单") }
        OutlinedButton(onClick={vm.temporaryAllow()},enabled=!vm.busy) { Text("允许本次（未来5分钟）") }
    } else Text("号码未知，不能添加号码标签或名单。")
    HorizontalDivider()
    Text("AI 解释（与当时规则分开）",style=MaterialTheme.typography.titleMedium)
    vm.explanation?.let { result ->
        Text(com.callguard.app.ai.AiExplanationClient.display(result))
        if(!simple && result.has("model")) Text("模型：${result.optString("model")}；提示版本：${result.optString("prompt_version")}")
    } ?: Text("尚未生成解释")
    if(vm.aiBusy) {LinearProgressIndicator();Text("正在异步生成解释，本地筛查继续工作")}
    Button(onClick={confirmAi=true},enabled=vm.aiEnabled && vm.aiConfigured && !vm.aiBusy) {Text("通话结束后生成 AI 解释")}
    if(!vm.aiEnabled)Text("云端 AI 当前关闭")
    if(confirmAi) AlertDialog(onDismissRequest={confirmAi=false},title={Text("确认这通电话已结束？")},
        text={Text("应用无法自动确认通话结束。仅在这通电话已结束时继续，将发送该记录的规则特征给已配置的后端模型服务。")},
        confirmButton={TextButton(onClick={confirmAi=false;vm.explainEndedCall()}) {Text("通话已结束，生成解释")}},
        dismissButton={TextButton(onClick={confirmAi=false}) {Text("取消")}})
    confirm?.let { action ->
        val allow=action==FeedbackAction.ALLOW
        AlertDialog(onDismissRequest={confirm=null}, title={Text(if(allow) "加入白名单？" else "加入黑名单？")},
            text={Text(if(allow) "将关闭该号码现有黑名单规则，后续来电优先放行。历史记录不变。" else "将关闭该号码现有白名单规则，后续非联系人来电将被拦截。历史记录不变。")},
            confirmButton={TextButton(onClick={confirm=null;vm.feedback(action)}) { Text("确认") }},
            dismissButton={TextButton(onClick={confirm=null}) { Text("取消") }})
    }
}
