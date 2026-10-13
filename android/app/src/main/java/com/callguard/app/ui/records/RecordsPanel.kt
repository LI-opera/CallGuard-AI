package com.callguard.app.ui.records
import androidx.compose.material3.*
import androidx.compose.runtime.*
@Composable fun RecordsPanel(vm:CallRecordViewModel,foregroundRevision:Int) {
    LaunchedEffect(foregroundRevision) { vm.refresh() }
    val app=androidx.compose.ui.platform.LocalContext.current.applicationContext
    val prefs=remember { app.getSharedPreferences("display",0) }
    var simple by remember { mutableStateOf(prefs.getBoolean("simple",true)) }
    Text("简明模式（隐藏分数和技术术语）")
    Switch(checked=simple,onCheckedChange={simple=it;prefs.edit().putBoolean("simple",it).apply()})
    AiSettingsPanel(vm)
    if(vm.busy) LinearProgressIndicator()
    if(vm.message.isNotBlank()) Text(vm.message)
    OutlinedButton(onClick={vm.refresh()},enabled=!vm.busy) { Text("刷新记录") }
    if(vm.selected!=null) CallDetailScreen(vm,simple) else {
        Text("今日已保存记录",style=MaterialTheme.typography.titleLarge)
        Text("放行 ${vm.summary.allowed} · 静音 ${vm.summary.silenced} · 拦截 ${vm.summary.blocked}")
        Text("其中降级 ${vm.summary.fallback} 次；统计提交动作，不统计实际接听或响铃。")
        Text("来电记录",style=MaterialTheme.typography.titleLarge)
        Text("号码未保存，意图未分析。点击详情可标记或设置名单。")
        if(vm.rows.isEmpty()) Text("暂无已保存记录")
        vm.rows.forEach { record -> key(record.id) {
            HorizontalDivider()
            Text(recordTime(record));Text(actionText(record.decision))
            Text("号码未保存 · 意图未分析")
            Text(reasonText(record.matchedRules))
            OutlinedButton(onClick={vm.open(record.id)},enabled=!vm.busy) { Text("查看详情 · ${record.id.take(8)}") }
        } }
        if(vm.more && vm.rows.isNotEmpty()) OutlinedButton(onClick={vm.nextPage()},enabled=!vm.busy) { Text("加载更多") }
    }
}
