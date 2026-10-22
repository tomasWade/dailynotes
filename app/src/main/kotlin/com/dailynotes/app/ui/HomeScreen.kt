package com.dailynotes.app.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.dailynotes.app.diary.DiaryEntry
import com.dailynotes.app.diary.DiaryParser
import java.time.LocalDate
import java.time.YearMonth

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(viewModel: HomeViewModel = viewModel()) {
    val state by viewModel.state.collectAsState()
    var editing by rememberSaveable { mutableStateOf<String?>(null) }   // 完整 relPath
    var reading by rememberSaveable { mutableStateOf<Int?>(null) }      // 阅读 pager 页码
    var calendarMode by rememberSaveable { mutableStateOf(false) }
    var calMonthText by rememberSaveable { mutableStateOf(YearMonth.now().toString()) }
    val calMonth = rememberSaveable(calMonthText) { YearMonth.parse(calMonthText) }

    fun openDate(date: LocalDate) {
        val idx = state.entries.indexOfFirst { it.date == date }
        if (idx >= 0) reading = idx else editing = "diary/" + DiaryParser.fileNameOf(date)
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
            },
            onSaved = viewModel::loadEntries,
            viewModel = editorVm,
        )
        return
    }

    val readPage = reading
    if (readPage != null && state.entries.isNotEmpty()) {
        DiaryPagerScreen(
            entries = state.entries,
            initialPage = readPage,
            dataVersion = state.entriesVersion,
            onEdit = { editing = it },
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
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (state.loading) CircularProgressIndicator(Modifier.padding(2.dp))
                TextButton(
                    onClick = { calendarMode = !calendarMode },
                ) {
                    Text(if (calendarMode) "列表" else "日历")
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
                onMonthChange = { calMonthText = it.toString() },
                dates = state.dateSet,
                onPick = ::openDate,
            )
        } else {
            if (state.entries.isEmpty() && !state.loading) {
                Text(
                    "还没有日记。点「今天」写下第一篇，或切到「日历」补记任意一天。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(top = 32.dp),
                )
            }

            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val grouped = state.entries.groupBy { "${it.date.year}年${it.date.monthValue}月" }
                grouped.forEach { (month, list) ->
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
