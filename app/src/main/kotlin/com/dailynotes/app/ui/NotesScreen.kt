package com.dailynotes.app.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dailynotes.app.note.NoteEntry
import com.dailynotes.app.note.NoteParser

@Composable
fun NotesScreen(viewModel: NotesViewModel = viewModel()) {
    val state by viewModel.state.collectAsState()
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf<NoteEntry?>(null) }

    val relPath = editing
    if (relPath != null) {
        val editorVm: EditorViewModel = viewModel()
        EditorScreen(
            relPath = relPath,
            title = "笔记",
            onBack = {
                editorVm.discard()
                editing = null
            },
            onSaved = viewModel::loadEntries,
            viewModel = editorVm,
        )
        return
    }

    Scaffold(
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { editing = "notes/" + NoteParser.generateName() },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("记一笔") },
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
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("笔记", style = MaterialTheme.typography.titleLarge)
                if (state.loading) CircularProgressIndicator(Modifier.padding(2.dp))
            }

            state.info?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            state.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }

            if (state.entries.isEmpty() && !state.loading) {
                Text(
                    "还没有笔记。点右下角「记一笔」。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(top = 32.dp),
                )
            }

            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(state.entries, key = { it.fileName }) { entry ->
                    NoteCard(
                        entry,
                        onClick = { editing = "notes/" + entry.fileName },
                        onLongClick = { confirmDelete = entry },
                    )
                }
            }
        }
    }

    confirmDelete?.let { entry ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("删除这条笔记？") },
            text = { Text(entry.preview.take(80)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = null
                    viewModel.deleteEntry(entry.fileName)
                }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = null }) { Text("取消") }
            },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NoteCard(entry: NoteEntry, onClick: () -> Unit, onLongClick: () -> Unit) {
    Card(
        Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (entry.preview.isNotBlank()) {
                Text(entry.preview, style = MaterialTheme.typography.bodyMedium, maxLines = 6)
            } else {
                Text(
                    "（空白）",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
            entry.firstImage?.let { EntryThumb(it) }
            Text(
                entry.fileName.removeSuffix(".md"),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}
