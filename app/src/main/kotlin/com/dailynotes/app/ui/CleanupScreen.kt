package com.dailynotes.app.ui

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.dailynotes.app.data.SafCache
import com.dailynotes.app.data.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 附件回收站：扫描零引用附件 → 缩略图网格（点开看大图/可保存到本地）→ 勾选 → 删除（远端+缓存） */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun CleanupScreen(viewModel: MeViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsState()
    var selected by rememberSaveable { mutableStateOf<Set<String>>(emptySet()) }
    var viewImage by rememberSaveable { mutableStateOf<String?>(null) }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pendingSave by rememberSaveable { mutableStateOf<String?>(null) }

    // 「另存为」系统对话框：确认后把附件从缓存复制到用户选的位置
    val saveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("image/*"),
    ) { target: Uri? ->
        val src = pendingSave
        pendingSave = null
        if (target != null && src != null) {
            scope.launch {
                val msg = withContext(Dispatchers.IO) {
                    runCatching {
                        val treeUri = SettingsStore(context).treeUri()
                        if (treeUri.isBlank()) error("未选择缓存文件夹")
                        val bytes = SafCache(context, treeUri).read(src)
                            ?: error("缓存文件不存在：$src")
                        context.contentResolver.openOutputStream(target, "wt")?.use {
                            it.write(bytes)
                        } ?: error("无法打开目标位置")
                    }.fold(
                        onSuccess = { "已保存" },
                        onFailure = { "保存失败：${it.message}" },
                    )
                }
                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun save(relPath: String) {
        pendingSave = relPath
        saveLauncher.launch(relPath.substringAfterLast('/'))
    }

    // 进入页面自动扫描一次
    LaunchedEffect(Unit) {
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
                "扫描缓存中所有日记/笔记的图片引用，找出不再被任何正文引用的孤儿附件。" +
                    "点缩略图看大图（可保存到本地），勾选后删除会同时清理服务器与本地缓存。",
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
                        "发现 ${state.orphans.size} 个孤儿附件（长按可保存）",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    val allSelected = selected.size == state.orphans.size
                    TextButton(onClick = {
                        selected = if (allSelected) emptySet() else state.orphans.toSet()
                    }) {
                        Text(if (allSelected) "取消全选" else "全选")
                    }
                }

                LazyVerticalGrid(
                    columns = GridCells.Adaptive(110.dp),
                    Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(state.orphans, key = { it }) { path ->
                        val checked = path in selected
                        Column {
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .aspectRatio(1f)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant)
                                    .combinedClickable(
                                        onClick = { viewImage = path },
                                        onClickLabel = "查看大图",
                                        onLongClick = { save(path) },
                                        onLongClickLabel = "保存到本地",
                                    ),
                            ) {
                                AttachmentImage(
                                    imagePath = path,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize(),
                                )
                                Checkbox(
                                    checked = checked,
                                    onCheckedChange = {
                                        selected = if (checked) selected - path else selected + path
                                    },
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .background(Color.Black.copy(alpha = 0.35f), RoundedCornerShape(50))
                                        .padding(1.dp),
                                )
                            }
                            Text(
                                path.substringAfterLast('/'),
                                fontSize = 10.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = MaterialTheme.colorScheme.outline,
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

    // 全屏看图：点任意处关闭，底部可保存到本地
    viewImage?.let { ref ->
        Dialog(
            onDismissRequest = { viewImage = null },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Column(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black),
            ) {
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .clickable { viewImage = null },
                    contentAlignment = Alignment.Center,
                ) {
                    AttachmentImage(
                        imagePath = ref,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        // 不用 navigationBarsPadding：全屏 Dialog 窗口上报的 inset 异常（实测把按钮压到 4px）
                        .padding(bottom = 56.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(
                        onClick = {
                            val current = ref
                            viewImage = null
                            save(current)
                        },
                    ) { Text("保存到本地", color = Color.White) }
                }
            }
        }
    }
}
