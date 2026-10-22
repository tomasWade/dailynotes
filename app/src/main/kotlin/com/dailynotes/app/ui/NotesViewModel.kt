package com.dailynotes.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.dailynotes.app.data.CacheSyncer
import com.dailynotes.app.data.SettingsStore
import com.dailynotes.app.note.NoteEntry
import com.dailynotes.app.note.NoteParser
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class NotesViewModel(app: Application) : AndroidViewModel(app) {

    data class UiState(
        val loading: Boolean = false,
        val entries: List<NoteEntry> = emptyList(),
        val error: String? = null,
        val info: String? = null,
    )

    private val store = SettingsStore(app)
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    init {
        loadEntries()
    }

    fun loadEntries() {
        if (store.treeUri().isBlank()) {
            _state.value = UiState(error = "请先到「设置」选择缓存文件夹")
            return
        }
        viewModelScope.launch {
            try {
                val entries = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val syncer = CacheSyncer(getApplication(), store)
                syncer.listCache("notes")
                    .filter { NoteParser.isNote(it) }
                    .sortedDescending()
                    .mapNotNull { name ->
                        val content = syncer.readCache("notes/$name")?.toString(Charsets.UTF_8) ?: ""
                        NoteParser.parse(name, content)
                    }
            }
            _state.value = _state.value.copy(entries = entries, loading = false)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = _state.value.copy(error = "读取本地缓存失败：${e.message}")
            }
        }
    }

    /** 长按删除：远端删成功才落本地（B 档）；失败仅提示 */
    fun deleteEntry(fileName: String) {
        if (store.treeUri().isBlank()) return
        _state.value = _state.value.copy(loading = true, error = null)
        viewModelScope.launch {
            try {
                val syncer = CacheSyncer(getApplication(), store)
                if (!syncer.serverConfigured()) {
                    _state.value = _state.value.copy(loading = false, error = "未配置服务器，无法同步删除")
                    return@launch
                }
                syncer.delete("notes/$fileName")
                loadEntries()
                _state.value = _state.value.copy(loading = false, info = "已删除 $fileName")
            } catch (e: Exception) {
                loadEntries()
                _state.value = _state.value.copy(loading = false, error = "删除失败：${e.message}")
            }
        }
    }
}
