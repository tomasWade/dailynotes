package com.dailynotes.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dailynotes.app.BuildConfig
import java.time.LocalDate
import java.time.format.DateTimeFormatter

@Composable
fun MeScreen(viewModel: MeViewModel = viewModel()) {
    val state by viewModel.state.collectAsState()
    var showCleanup by rememberSaveable { mutableStateOf(false) }

    if (showCleanup) {
        CleanupScreen(viewModel = viewModel, onBack = { showCleanup = false })
        return
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri -> uri?.let(viewModel::exportTo) }

    Column(
        Modifier
            .fillMaxSize()
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("我", style = MaterialTheme.typography.titleLarge)

        state.error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        }
        state.info?.let {
            Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium)
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("统计", style = MaterialTheme.typography.titleMedium)
                StatRow("日记", "${state.stats.diaryCount} 篇 · 连续 ${state.stats.streakDays} 天")
                StatRow("笔记", "${state.stats.noteCount} 条")
                StatRow("附件", "${state.stats.attachmentCount} 张")
                StatRow("上次同步", viewModel.lastSyncLabel())
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("数据", style = MaterialTheme.typography.titleMedium)
                Text(
                    "所有数据是缓存文件夹里的纯 Markdown 文件与图片，随时可用其他工具打开。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            exportLauncher.launch(
                                "dailynotes-backup-" +
                                    LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE) + ".zip"
                            )
                        },
                        enabled = !state.loading,
                    ) {
                        if (state.loading) {
                            CircularProgressIndicator(Modifier.padding(2.dp))
                        } else {
                            Text("导出全部数据 (zip)")
                        }
                    }
                    OutlinedButton(onClick = { showCleanup = true }, enabled = !state.loading) {
                        Text("附件清理")
                    }
                }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("关于", style = MaterialTheme.typography.titleMedium)
                Text("dailynotes v${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodySmall)
                Text(
                    "服务端：SFTPGo (WebDAV) · 同步：LWW + etag 对账",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }
    }
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}
