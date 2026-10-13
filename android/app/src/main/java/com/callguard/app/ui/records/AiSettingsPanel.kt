package com.callguard.app.ui.records
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.text.input.PasswordVisualTransformation
@Composable fun AiSettingsPanel(vm:CallRecordViewModel) {
    var configure by remember {mutableStateOf(false)}
    var endpoint by remember {mutableStateOf(vm.aiSettings.endpoint)}
    var key by remember {mutableStateOf("")}
    Text("云端 AI 解释（通话结束后手动生成）",style=MaterialTheme.typography.titleMedium)
    Text("只上传规则特征，不上传号码、联系人或录音。解释不改变拦截动作。")
    Switch(checked=vm.aiEnabled,onCheckedChange={vm.toggleAi(it)},enabled=vm.aiConfigured)
    OutlinedButton(onClick={configure=!configure}) {Text(if(configure) "收起后端配置" else "配置后端服务")}
    if(configure) {
        OutlinedTextField(value=endpoint,onValueChange={endpoint=it},label={Text("后端地址")})
        OutlinedTextField(value=key,onValueChange={key=it},label={Text("后端访问密钥（不是模型密钥）")},visualTransformation=PasswordVisualTransformation())
        Button(onClick={vm.configureAi(endpoint,key);key=""}) {Text("保存配置")}
    }
    if(vm.aiMessage.isNotBlank())Text(vm.aiMessage)
}
