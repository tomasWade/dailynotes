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
) {
    val state by viewModel.state.collectAsState()
    var previewMode by rememberSaveable { mutableStateOf(false) }
    var modeDecided by rememberSaveable { mutableStateOf(false) }
    var viewImage by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(relPath) {
        modeDecided = false
        viewModel.load(relPath)
    }

    // 打开已有内容 → 预览态；新文件/空白 → 编辑态。只决定一次（手动切换、旋转不覆盖）
    LaunchedEffect(state.initialPreview) {
        val initial = state.initialPreview
        if (!modeDecided && initial != null) {
            previewMode = initial
            modeDecided = true
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
                    TextButton(onClick = { previewMode = !previewMode }) {
                        Text(if (previewMode) "编辑" else "预览")
                    }
                    if (onDelete != null) {
                        IconButton(onClick = { confirmDelete = true }) {
                            Icon(Icons.Filled.Delete, contentDescription = "删除")
                        }
                    }
                    if (!previewMode) {
                        IconButton(
                            onClick = {
                                pickImage.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                )
                            },
                            enabled = !state.loading,
                        ) {
                            Icon(Icons.Filled.Add, contentDescription = "插入图片")
                        }
                        Spacer(Modifier.width(4.dp))
                        Button(
                            onClick = { viewModel.save(onSaved) },
                            enabled = !state.loading && !state.saving,
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
            if (state.loading) {
                Text("加载中…", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(4.dp))
            }

            if (previewMode) {
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
        Dialog(onDismissRequest = { viewImage = null }) {
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
