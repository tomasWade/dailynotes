package com.dailynotes.app.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.dailynotes.app.data.CacheState
import com.dailynotes.app.data.SettingsStore
import com.dailynotes.app.diary.DiaryParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class MeViewModel(app: Application) : AndroidViewModel(app) {

    data class Stats(
        val diaryCount: Int = 0,
        val streakDays: Int = 0,
        val noteCount: Int = 0,
        val attachmentCount: Int = 0,
        val lastSync: Long = 0,
    )

    data class UiState(
        val loading: Boolean = false,
        val stats: Stats = Stats(),
        val error: String? = null,
        val info: String? = null,
    )

    private val store = SettingsStore(app)
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    init {
        loadStats()
    }

    fun loadStats() {
        if (store.treeUri().isBlank()) {
            _state.value = UiState(error = "请先到「设置」选择缓存文件夹")
            return
        }
        viewModelScope.launch {
            val cache = com.dailynotes.app.data.SafCache(getApplication(), store.treeUri())
            val state = CacheState.parse(cache.readState())
            // 事实以缓存目录为准（B 档全量缓存），cache-state 兜底附件计数
            val diaries = cache.list("diary").filter { DiaryParser.isDiary(it) }
            val dates = diaries.mapNotNull { DiaryParser.parseDate(it) }.toSet()
            val attachments = state.files.keys.count { it.startsWith("attachments/") }
            _state.value = UiState(
                stats = Stats(
                    diaryCount = diaries.size,
                    streakDays = DiaryParser.streak(dates),
                    noteCount = cache.list("notes").count { com.dailynotes.app.note.NoteParser.isNote(it) },
                    attachmentCount = attachments,
                    lastSync = state.lastSync,
                ),
            )
        }
    }

    /** 导出全部数据为 zip（三目录递归 + cache-state），写入用户选的目标 */
    fun exportTo(uri: Uri) {
        if (store.treeUri().isBlank()) {
            _state.value = _state.value.copy(error = "请先到「设置」选择缓存文件夹")
            return
        }
        _state.value = _state.value.copy(loading = true, error = null, info = null)
        viewModelScope.launch {
            try {
                val cache = com.dailynotes.app.data.SafCache(getApplication(), store.treeUri())
                val count = withContext(Dispatchers.IO) {
                    val resolver = getApplication<Application>().contentResolver
                    var n = 0
                    resolver.openOutputStream(uri, "wt")?.use { out ->
                        ZipOutputStream(out.buffered()).use { zip ->
                            for (dir in listOf("diary", "notes", "attachments")) {
                                for (rel in cache.listRecursive(dir)) {
                                    val bytes = cache.read(rel) ?: continue
                                    zip.putNextEntry(ZipEntry(rel))
                                    zip.write(bytes)
                                    zip.closeEntry()
                                    n++
                                }
                            }
                            cache.readState()?.let { s ->
                                zip.putNextEntry(ZipEntry(".dailynotes/cache-state.json"))
                                zip.write(s.toByteArray(Charsets.UTF_8))
                                zip.closeEntry()
                            }
                        }
                    } ?: throw IllegalStateException("无法打开导出目标")
                    n
                }
                _state.value = _state.value.copy(loading = false, info = "已导出 $count 个文件")
            } catch (e: Exception) {
                _state.value = _state.value.copy(loading = false, error = "导出失败：${e.message}")
            }
        }
    }

    fun lastSyncLabel(): String {
        val ts = _state.value.stats.lastSync
        if (ts <= 0) return "从未"
        return DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").format(
            java.time.Instant.ofEpochMilli(ts).atZone(java.time.ZoneId.systemDefault())
        )
    }
}
