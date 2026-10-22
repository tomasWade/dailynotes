package com.dailynotes.app.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dailynotes.app.diary.DiaryParser

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(
    relPath: String,
    title: String,
    onBack: () -> Unit,
    onSaved: () -> Unit,
    viewModel: EditorViewModel = viewModel(),
    onDelete: (() -> Unit)? = null,
    // 从阅读页点「编辑」进入时传 true：用户意图明确是编辑，跳过预览态直接进编辑框
    // （否则用户要在阅读页点一次编辑、编辑器里再点一次——「第二次才能编辑」的根源，v0.2.8）
    startInEditMode: Boolean = false,
) {
    val state by viewModel.state.collectAsState()
    // 三态：null=load 未决（不渲染编辑/预览框架，防「先进编辑再弹回预览」的初值闪现，v0.2.7）
    var previewMode by rememberSaveable { mutableStateOf<Boolean?>(null) }
    var modeDecided by rememberSaveable { mutableStateOf(false) }
    var viewImage by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }

    // startInEdit 只对「本次进入」生效一次：旋转/进程重建后不重放（否则会把用户手动切回的预览强拉回编辑，review L1）
    var startInEditApplied by rememberSaveable { mutableStateOf<String?>(null) }

    LaunchedEffect(relPath) {
        if (startInEditMode && startInEditApplied != relPath) {
            startInEditApplied = relPath
            // 直接编辑模式：load 未决期间就先定编辑态（spinner 消失即编辑框，无预览中转）
            previewMode = false
            modeDecided = true
        }
        if (startInEditMode) {
            modeDecided = true // 重建路径（startInEditApplied==relPath）：仍守住 decided，防止迟到 initialPreview 覆盖手动切换
        } else {
            modeDecided = false
            startInEditApplied = null
        }
        com.dailynotes.app.data.DiagLog.log("editor-ui", "enter[$relPath] modeDecided=$modeDecided previewMode=$previewMode startInEdit=$startInEditMode")
        viewModel.load(relPath)
    }

    // 打开已有内容 → 预览态；新文件/空白 → 编辑态。只决定一次（手动切换、旋转不覆盖）
    LaunchedEffect(state.initialPreview) {
        val initial = state.initialPreview
        com.dailynotes.app.data.DiagLog.log("editor-ui", "initialPreview effect fired: initial=$initial modeDecided=$modeDecided previewMode=$previewMode")
        if (!modeDecided && initial != null) {
            previewMode = initial
            modeDecided = true
            com.dailynotes.app.data.DiagLog.log("editor-ui", "decide: previewMode=$initial")
        }
    }

    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let(viewModel::insertImage)
    }

    BackHandler(onBack = onBack)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    // 手动切换即视为「已决定」：用户在 load 完成前点的切换不许被迟到的 initialPreview 覆盖
                    // （v0.2.6 前症状：load 慢时点「编辑」→ load 完成被强拉回预览，要点两次）
                    TextButton(
                        onClick = {
                            val old = previewMode
                            previewMode = !(previewMode ?: true); modeDecided = true
                            com.dailynotes.app.data.DiagLog.log("editor-ui", "manual toggle: $old -> $previewMode")
                        },
                        enabled = previewMode != null,
                    ) {
                        Text(if (previewMode == true) "编辑" else "预览")
                    }
                    if (onDelete != null) {
                        IconButton(onClick = { confirmDelete = true }) {
                            Icon(Icons.Filled.Delete, contentDescription = "删除")
                        }
                    }
                    if (previewMode == false) {
                        IconButton(
                            onClick = {
                                pickImage.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                )
                            },
                            enabled = !state.loading && !state.loadFailed,
                        ) {
                            Icon(Icons.Filled.Add, contentDescription = "插入图片")
                        }
                        Spacer(Modifier.width(4.dp))
                        Button(
                            onClick = { viewModel.save(onSaved) },
                            enabled = !state.loading && !state.saving && !state.loadFailed,
                            modifier = Modifier.padding(end = 8.dp),
                        ) {
                            if (state.saving) {
                                CircularProgressIndicator(Modifier.height(18.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(6.dp))
                            }
                            Text("保存")
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 12.dp),
        ) {
            if (state.loading || (previewMode == null && !state.loadFailed)) {
                // load 未决（loading 或 previewMode 三态的 null）：整页占位，不渲染编辑/预览框架
                // （旧版初值 previewMode=false 会先闪编辑器骨架，load 完成又跳预览——「先进编辑再弹回」）
                // loadFailed 例外：渲染编辑框+错误行+禁用保存（永久转圈像挂死，R10-M2）
                Box(
                    Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            } else if (previewMode == true) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(vertical = 8.dp),
                ) {
                    if (state.text.isBlank()) {
                        Text(
                            "（空白）",
                            color = MaterialTheme.colorScheme.outline,
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    } else {
                        MarkdownPreview(state.text)
                    }
                }
            } else {
                OutlinedTextField(
                    value = state.text,
                    onValueChange = viewModel::updateText,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    placeholder = { Text("写点什么…") },
                    textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
                )

                // 当前正文引用的图片缩略条：点开全屏看大图
                val refs = DiaryParser.attachmentRefs(state.text)
                if (refs.isNotEmpty()) {
                    LazyRow(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        items(refs.size) { i ->
                            AttachmentImage(
                                imagePath = refs[i],
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .height(72.dp)
                                    .width(72.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .clickable { viewImage = refs[i] },
                            )
                        }
                    }
                }
            }

            Row(
                Modifier
                    .fillMaxWidth()
                    .height(40.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                when {
                    state.savedOk -> Text("已保存并同步", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
                    state.error != null -> Text(
                        state.error!!,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    else -> Text(
                        "Markdown · 图片会存入 attachments/",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }
        }
    }

    // 删除确认
    if (confirmDelete && onDelete != null) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("删除？") },
            text = { Text("删除后同时移除服务器与本地缓存，无法撤销。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    onDelete()
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("取消") }
            },
        )
    }

    // 全屏看图
    viewImage?.let { ref ->
        Dialog(
            onDismissRequest = { viewImage = null },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black)
                    .clickable { viewImage = null },
                contentAlignment = Alignment.Center,
            ) {
                AttachmentImage(
                    imagePath = ref,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}
