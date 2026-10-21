package com.dailynotes.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** 附件回收站：扫描零引用附件 → 勾选 → 删除（远端+缓存） */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CleanupScreen(viewModel: MeViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsState()
    var selected by rememberSaveable { mutableStateOf<Set<String>>(emptySet()) }

    // 进入页面自动扫描一次
    androidx.compose.runtime.LaunchedEffect(Unit) {
        if (state.orphans.isEmpty() && !state.scanning) viewModel.scanOrphans()
    }

    fun rescan() {
        selected = emptySet()
        viewModel.scanOrphans()
    }

    BackHandler(onBack = onBack)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("附件清理") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "扫描缓存中所有日记/笔记的图片引用，找出不再被任何正文引用的孤儿附件。删除会同时清理服务器与本地缓存。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(onClick = ::rescan, enabled = !state.scanning) {
                    Text(if (state.orphans.isEmpty()) "扫描" else "重新扫描")
                }
                if (state.scanning) CircularProgressIndicator(Modifier.padding(4.dp))
                state.cleanupInfo?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
            }

            if (!state.scanning && state.orphans.isEmpty() && state.cleanupInfo == null) {
                Text(
                    "尚未扫描。点「扫描」查找孤儿附件。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(top = 24.dp),
                )
            }

            if (state.orphans.isNotEmpty()) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "发现 ${state.orphans.size} 个孤儿附件",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    val allSelected = selected.size == state.orphans.size
                    TextButton(onClick = {
                        selected = if (allSelected) emptySet() else state.orphans.toSet()
                    }) {
                        Text(if (allSelected) "取消全选" else "全选")
                    }
                }

                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    items(state.orphans, key = { it }) { path ->
                        val checked = path in selected
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    selected = if (checked) selected - path else selected + path
                                }
                                .padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = checked,
                                onCheckedChange = {
                                    selected = if (checked) selected - path else selected + path
                                },
                            )
                            Text(
                                path.removePrefix("attachments/"),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }

                Button(
                    onClick = {
                        viewModel.deleteOrphans(selected.toList())
                        selected = emptySet()
                    },
                    enabled = selected.isNotEmpty() && !state.scanning,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("删除所选 (${selected.size})")
                }
            }
        }
    }
}
