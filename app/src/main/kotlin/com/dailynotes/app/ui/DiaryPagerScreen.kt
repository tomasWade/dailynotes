package com.dailynotes.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue

import com.dailynotes.app.data.SettingsStore
import com.dailynotes.app.diary.DiaryEntry
import com.dailynotes.app.diary.DiaryParser
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.window.Dialog
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color

/** 日记阅读页：左右滑动切换上一篇/下一篇，顶栏进入编辑 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiaryPagerScreen(
    entries: List<DiaryEntry>,
    initialPage: Int,
    dataVersion: Long,
    onEdit: (String) -> Unit,
    onBack: () -> Unit,
) {
    if (entries.isEmpty()) {
        onBack()
        return
    }
    val pagerState = rememberPagerState(initialPage = initialPage.coerceIn(0, entries.lastIndex)) {
        entries.size
    }
    // entries 重建（同步后）必须重算派生状态，否则标题停留旧数据
    val current by remember(entries) {
        derivedStateOf { entries[pagerState.currentPage.coerceIn(0, entries.lastIndex)] }
    }

    BackHandler(onBack = onBack)

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            DiaryParser.dateLabel(current.date),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            "${pagerState.currentPage + 1} / ${entries.size}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    TextButton(onClick = { onEdit("diary/" + current.fileName) }) {
                        Text("编辑")
                    }
                },
            )
        },
    ) { padding ->
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) { page ->
            val entry = entries[page]
            PagerPageContent(relPath = "diary/" + entry.fileName, dataVersion = dataVersion)
        }
    }
}

@Composable
private fun PagerPageContent(relPath: String, dataVersion: Long) {
    val context = LocalContext.current
    var text by rememberSaveable(relPath) { mutableStateOf<String?>(null) }
    var viewImage by rememberSaveable { mutableStateOf<String?>(null) }

    // 页面进入视野/数据版本变化时读缓存（编辑保存返回后当前页能刷新）；本地 IO 离开主线程。
    // 缓存缺失时走 recover 从远端拉回（与编辑器行为一致，否则这里显示空白）
    LaunchedEffect(relPath, dataVersion) {
        val t0 = android.os.SystemClock.elapsedRealtime()
        text = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val syncer = com.dailynotes.app.data.CacheSyncer(context, SettingsStore(context))
            val r = runCatching {
                syncer.readCache(relPath)?.toString(Charsets.UTF_8)
                    ?: syncer.recover(relPath)?.toString(Charsets.UTF_8)
            }.getOrNull()
            com.dailynotes.app.data.DiagLog.log(
                "pager",
                "page[$relPath] v$dataVersion ${android.os.SystemClock.elapsedRealtime() - t0}ms -> ${if (r == null) "null" else "${r.length}ch"}",
            )
            r
        } ?: ""
    }

    val content = text
    if (content == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        if (content.isBlank()) {
            Text(
                "（空白）",
                color = MaterialTheme.colorScheme.outline,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(vertical = 24.dp),
            )
        } else {
            MarkdownPreview(content)
        }

        // 文内图片缩略条：点开全屏
        val refs = DiaryParser.attachmentRefs(content)
        if (refs.isNotEmpty()) {
            LazyRow(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
            ) {
                items(refs.size) { i ->
                    AttachmentImage(
                        imagePath = refs[i],
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .padding(end = 6.dp)
                            .size(72.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .clickable { viewImage = refs[i] },
                    )
                }
            }
        }
        androidx.compose.foundation.layout.Spacer(Modifier.padding(bottom = 32.dp))
    }

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
