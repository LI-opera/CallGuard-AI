package com.callguard.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import com.callguard.app.ui.records.RecordsPanel
import com.callguard.app.ui.records.CallRecordViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.callguard.app.platform.CallScreeningRoleGateway
import com.callguard.app.platform.RoleState

class MainActivity : ComponentActivity() {
    private var foregroundRevision by mutableIntStateOf(0)
    private var contactsGranted by mutableStateOf(false)
    private val contactsRequest = registerForActivityResult(ActivityResultContracts.RequestPermission()) { contactsGranted = it }
    override fun onResume() {
        super.onResume()
        foregroundRevision++
        contactsGranted = checkSelfPermission(android.Manifest.permission.READ_CONTACTS) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }
    private lateinit var gateway: CallScreeningRoleGateway
    private var roleState by mutableStateOf(RoleState.UNSUPPORTED)
    private var requestError by mutableStateOf(false)
    private val roleRequest = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        roleState = gateway.state()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        gateway = CallScreeningRoleGateway(this)
        gateway.observeRoleLoss(this) { roleState = it }
        val records = ViewModelProvider(this)[CallRecordViewModel::class.java]
        setContent {
            MaterialTheme {
                Scaffold { padding ->
                    Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text("CallGuard AI", style = MaterialTheme.typography.headlineMedium)
                        Text(when (roleState) {
                            RoleState.UNSUPPORTED -> "此设备不支持来电筛查角色"
                            RoleState.NOT_HELD -> "未授权，或来电筛查角色已失效"
                            RoleState.HELD -> "已获得来电筛查角色"
                        })
                        Text("本地筛查验证版：按联系人、本地名单和规则评分放行、静音或拦截。当前使用标准模式，查询异常或超时默认放行。实时来电筛查不使用 AI；通话后解释可选开启。")
                        Text(if (contactsGranted) "已允许联系人读取：联系人优先放行" else "未允许联系人读取：按系统规则仅筛查非联系人来电")
                        if (!contactsGranted) Button(onClick = { contactsRequest.launch(android.Manifest.permission.READ_CONTACTS) }) {
                            Text("允许读取联系人")
                        }
                        if (roleState == RoleState.NOT_HELD) {
                            Button(onClick = { requestError = !gateway.requestRole(roleRequest) }) {
                                Text("申请来电筛查角色")
                            }
                        }
                        if (requestError) Text("系统授权页面无法打开，请检查设备设置后重试。")
                        RecordsPanel(records, foregroundRevision)
                    }
                }
            }
        }
    }
}
