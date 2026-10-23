package com.dailynotes.app.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dailynotes.app.diary.DiaryEntry
import com.dailynotes.app.diary.DiaryParser
import java.time.LocalDate
import java.time.YearMonth
import kotlinx.coroutines.launch

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(viewModel: HomeViewModel = viewModel()) {
    val state by viewModel.state.collectAsState()
    var editing by rememberSaveable { mutableStateOf<String?>(null) }   // 完整 relPath
    var editFromReader by rememberSaveable { mutableStateOf(false) }    // 从阅读页点「编辑」进入：直接进编辑态（v0.2.8）
    var reading by rememberSaveable { mutableStateOf<Int?>(null) }      // 阅读 pager 页码
    var calendarMode by rememberSaveable { mutableStateOf(false) }
    var calMonthText by rememberSaveable { mutableStateOf(YearMonth.now().toString()) }
    val calMonth = rememberSaveable(calMonthText) { YearMonth.parse(calMonthText) }
    // 跳年/跳月（v0.3.2）：日历标题与列表「跳年」共用选择器
    var showJumpPicker by rememberSaveable { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    // 年份范围：数据最早年..max(数据最晚年, 当前年)。
    // key 用 entriesVersion（与下方 yearGroups 一致）：dateSet 的 O(n) equals 在每次重组时白跑（review LOW-4）
    val years = remember(state.entriesVersion) {
        val nowY = LocalDate.now().year
        val min = state.dateSet.minOfOrNull { it.year } ?: nowY
        val max = maxOf(state.dateSet.maxOfOrNull { it.year } ?: nowY, nowY)
        min..max
    }

    // 年→月双层分组 + 每个年组首条在 LazyColumn 的全局 index（跳年滚动用；含所有 sticky 头的计数）
    data class YearGroup(val year: Int, val startIndex: Int, val months: List<Pair<String, List<DiaryEntry>>>)
    val yearGroups = remember(state.entriesVersion) { // entries 变化必伴随 version 变化，Long 比较免 O(n) List.equals
        var idx = 0
        state.entries.groupBy { it.date.year }.map { (year, yearEntries) ->
            val start = idx
            idx++ // 年头
            val months = yearEntries
                .groupBy { "${it.date.year}年${it.date.monthValue}月" }
                .map { (k, v) -> idx++; idx += v.size; k to v }
            YearGroup(year, start, months)
        }
    }

    fun openDate(date: LocalDate) {
        val idx = state.entries.indexOfFirst { it.date == date }
        if (idx >= 0) reading = idx else { editing = "diary/" + DiaryParser.fileNameOf(date); editFromReader = false }
    }

    // 编辑器优先（从 pager 进入编辑，返回时回 pager）
    val editPath = editing
    if (editPath != null) {
        val editorVm: EditorViewModel = viewModel()
        val date = editPath.removePrefix("diary/").removeSuffix(".md")
        val shortTitle = DiaryParser.parseDate("$date.md")
            ?.let { "${it.monthValue}月${it.dayOfMonth}日" } ?: editPath
        EditorScreen(
            relPath = editPath,
            title = "日记 · $shortTitle",
            onBack = {
                editorVm.discard()
                editing = null
                editFromReader = false
            },
            onSaved = viewModel::loadEntries,
            viewModel = editorVm,
            startInEditMode = editFromReader,
        )
        return
    }

    val readPage = reading
    if (readPage != null && state.entries.isNotEmpty()) {
        DiaryPagerScreen(
            entries = state.entries,
            initialPage = readPage,
            dataVersion = state.entriesVersion,
            onEdit = { editing = it; editFromReader = true },
            onBack = { reading = null },
        )
        return
    }

    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("日记", style = MaterialTheme.typography.titleLarge)
            // 横向可滚：顶栏按钮组在 ≤360dp 屏 + 同步中进度圈会超出宽度（review LOW-1），
            // 溢出时 Compose 压缩最小宽度会挤掉标签——允许横向滑动即可
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.horizontalScroll(rememberScrollState()),
            ) {
                if (state.loading) CircularProgressIndicator(Modifier.padding(2.dp))
                TextButton(
                    onClick = { calendarMode = !calendarMode },
                ) {
                    Text(if (calendarMode) "列表" else "日历")
                }
                TextButton(onClick = { showJumpPicker = true }) {
                    Text("跳年")
                }
                Button(onClick = { openDate(LocalDate.now()) }, enabled = !state.loading) {
                    Text("今天")
                }
                Button(onClick = viewModel::refresh, enabled = !state.loading) {
                    Text("同步")
                }
            }
        }

        state.info?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        state.error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        }

        if (calendarMode) {
            CalendarPanel(
                month = calMonth,
                // 翻月夹在 years 范围内：picker 的 selYear 也夹同一范围，
                // 不夹会出现「翻到 2027 → 开 picker 被拉回 2026」的诡异回跳（review LOW-3）
                onMonthChange = { calMonthText = it.coerceIn(YearMonth.of(years.first, 1), YearMonth.of(years.last, 12)).toString() },
                dates = state.dateSet,
                onPick = ::openDate,
                onTitleClick = { showJumpPicker = true },
            )
        } else {
            if (state.entries.isEmpty() && !state.loading) {
                Text(
                    if (state.neverSynced) {
                        "本地缓存为空且尚未同步过。点上方「同步」从服务器拉取你的数据（首次约 20 秒）。"
                    } else {
                        "还没有日记。点「今天」写下第一篇，或切到「日历」补记任意一天。"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(top = 32.dp),
                )
            }

            // 年→月双层分组列表（v0.3.2）：年级大分隔头 +「更早 ↓」快捷键 + 回顶悬浮按钮
            val showBackToTop by remember {
                derivedStateOf { listState.firstVisibleItemIndex > 8 }
            }
            Box(Modifier.fillMaxSize()) {
                LazyColumn(
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    // 尾部留白：回顶 FAB 浮在内容上是标准行为，但滚到底时最后一张卡片要能完整露出
                    contentPadding = PaddingValues(bottom = 80.dp),
                ) {
                    yearGroups.forEachIndexed { yi, (year, _, months) ->
                        stickyHeader(key = "y-$year") {
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .background(MaterialTheme.colorScheme.surface)
                                    .padding(vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    "$year 年",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                // 倒序列表里更早的下一个年组（数据量大时免长滑）
                                if (yi + 1 < yearGroups.size) {
                                    val earlier = yearGroups[yi + 1]
                                    TextButton(onClick = { scope.launch { listState.scrollToItem(earlier.startIndex) } }) {
                                        Text("${earlier.year} 年 ↓")
                                    }
                                }
                            }
                        }
                        months.forEach { (month, list) ->
                            stickyHeader(key = "h-$month") {
                                Text(
                                    month,
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(MaterialTheme.colorScheme.surface)
                                        .padding(vertical = 4.dp),
                                )
                            }
                            items(list, key = { it.fileName }) { entry ->
                                DiaryCard(entry) {
                                    state.entries.indexOfFirst { it.fileName == entry.fileName }
                                        .takeIf { it >= 0 }?.let { reading = it }
                                }
                            }
                        }
                    }
                }
                // 回顶部悬浮按钮：跳年到旧年份后一键回最新
                androidx.compose.animation.AnimatedVisibility( // 嵌套 scope 下需 FQ 调顶层版本（ColumnScope 同名扩展会抢占解析）
                    visible = showBackToTop,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(bottom = 16.dp, end = 8.dp),
                ) {
                    FloatingActionButton(onClick = { scope.launch { listState.scrollToItem(0) } }) {
                        Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "回到顶部")
                    }
                }
            }
        }
    }

    // 年月快速跳转（日历标题 / 列表「跳年」共用）：日历模式跳月，列表模式跳年到该年第一篇
    if (showJumpPicker) {
        YearMonthPickerDialog(
            initial = if (calendarMode) calMonth else YearMonth.now(),
            years = years,
            onPick = { ym ->
                showJumpPicker = false
                if (calendarMode) {
                    calMonthText = ym.toString()
                } else {
                    val yg = yearGroups.firstOrNull { it.year == ym.year }
                    if (yg == null) {
                        // 选到没有日记的年份（空档年/未来年/空数据）静默无反应太突兀，明确告知（review LOW-2）
                        viewModel.setInfo("${ym.year} 年没有日记")
                    } else {
                        // 精确到月（review L2）：月头在年头后，逐月累计 offset；目标月无数据退回年头条
                        val mLabel = "${ym.year}年${ym.monthValue}月"
                        var off = yg.startIndex + 1
                        var found = false
                        for ((label, list) in yg.months) {
                            if (label == mLabel) { found = true; break }
                            off += 1 + list.size
                        }
                        val target = if (found) off else yg.startIndex
                        scope.launch { listState.scrollToItem(target) }
                    }
                }
            },
            onDismiss = { showJumpPicker = false },
        )
    }
}

@Composable
private fun DiaryCard(entry: DiaryEntry, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(DiaryParser.dateLabel(entry.date), style = MaterialTheme.typography.titleMedium)
                if (entry.firstImage != null) {
                    Text("📷", style = MaterialTheme.typography.labelMedium)
                }
            }
            if (entry.preview.isNotBlank()) {
                Text(entry.preview, style = MaterialTheme.typography.bodyMedium, maxLines = 6)
            } else {
                Text(
                    "（空白）",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
            entry.firstImage?.let { image ->
                EntryThumb(image)
            }
        }
    }
}
