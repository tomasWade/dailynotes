package com.dailynotes.app.ui

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

@Composable
fun HomeScreen(viewModel: HomeViewModel = viewModel()) {
    val state by viewModel.state.collectAsState()
    var editing by rememberSaveable { mutableStateOf<String?>(null) }   // 完整 relPath

    val relPath = editing
    if (relPath != null) {
        val editorVm: EditorViewModel = viewModel()
        val date = relPath.removePrefix("diary/").removeSuffix(".md")
        val title = DiaryParser.parseDate("$date.md")?.let { DiaryParser.dateLabel(it) } ?: relPath
        EditorScreen(
            relPath = relPath,
            title = "日记 · $title",
            onBack = {
                editorVm.discard()
                editing = null
            },
            onSaved = viewModel::loadEntries,
            viewModel = editorVm,
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
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.loading) CircularProgressIndicator(Modifier.padding(2.dp))
                Button(onClick = { editing = "diary/" + DiaryParser.fileNameOf(LocalDate.now()) }, enabled = !state.loading) {
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

        if (state.entries.isEmpty() && !state.loading) {
            Text(
                "还没有日记。点「今天」写下第一篇。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(top = 32.dp),
            )
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(state.entries, key = { it.fileName }) { entry ->
                DiaryCard(entry) { editing = "diary/" + entry.fileName }
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
