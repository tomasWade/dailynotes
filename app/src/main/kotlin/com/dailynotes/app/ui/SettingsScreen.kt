package com.dailynotes.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.dailynotes.app.data.SettingsStore
import com.dailynotes.sync.SyncEngine
import com.dailynotes.sync.WebDavSyncEngine
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    val store = remember { SettingsStore(context) }
    val scope = rememberCoroutineScope()

    var serverUrl by remember { mutableStateOf(store.serverUrl()) }
    var username by remember { mutableStateOf(store.username()) }
    var password by remember { mutableStateOf(store.password()) }
    var rootPath by remember { mutableStateOf(store.rootPath()) }
    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }
    var saved by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("服务器", style = MaterialTheme.typography.titleMedium)

        OutlinedTextField(
            value = serverUrl,
            onValueChange = { serverUrl = it; saved = false },
            label = { Text("地址（https://host:port）") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        OutlinedTextField(
            value = username,
            onValueChange = { username = it; saved = false },
            label = { Text("用户名") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        OutlinedTextField(
            value = password,
            onValueChange = { password = it; saved = false },
            label = { Text("密码") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
        )
        OutlinedTextField(
            value = rootPath,
            onValueChange = { rootPath = it; saved = false },
            label = { Text("远端根路径（默认 /）") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )

        Button(
            onClick = {
                store.save(serverUrl, username, password, rootPath)
                saved = true
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (saved) "已保存" else "保存") }

        Button(
            onClick = {
                testing = true
                testResult = null
                scope.launch {
                    testResult = try {
                        val engine: SyncEngine = WebDavSyncEngine(
                            baseUrl = (serverUrl.trimEnd('/') + "/" + rootPath.trim('/')).trimEnd('/'),
                            username = username,
                            password = password,
                        )
                        SyncEngine.TOP_DIRS.forEach { engine.mkdir(it) }
                        "连接成功，目录已就绪"
                    } catch (e: Exception) {
                        "失败：${e.message}"
                    }
                    testing = false
                }
            },
            enabled = serverUrl.isNotBlank() && username.isNotBlank() && !testing,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (testing) CircularProgressIndicator(modifier = Modifier.padding(top = 2.dp))
                Text("测试连接（建目录）")
            }
        }

        testResult?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodyMedium,
                color = if (it.startsWith("失败")) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.primary,
            )
        }
    }
}
