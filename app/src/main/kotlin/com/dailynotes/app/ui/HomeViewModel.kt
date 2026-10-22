package com.dailynotes.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.dailynotes.app.data.CacheSyncer
import com.dailynotes.app.data.SettingsStore
import com.dailynotes.app.diary.DiaryEntry
import com.dailynotes.app.diary.DiaryParser
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.update

class HomeViewModel(app: Application) : AndroidViewModel(app) {

    data class UiState(
        val loading: Boolean = false,
        val entries: List<DiaryEntry> = emptyList(),
        val dateSet: Set<java.time.LocalDate> = emptySet(),
        val error: String? = null,
        val info: String? = null,
        val entriesVersion: Long = 0,   // 列表每次重建 +1，阅读 pager 据此刷新当前页
    )

    private val store = SettingsStore(app)
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    init {
        refresh()
    }

    fun refresh() {
        if (store.treeUri().isBlank()) {
            _state.value = UiState(error = "请先到「设置」选择缓存文件夹")
            return
        }
        val syncer = CacheSyncer(getApplication(), store)
        if (!syncer.serverConfigured()) {
            loadEntries()
            _state.value = _state.value.copy(error = "未配置服务器，仅显示本地缓存（去「设置」配置）")
            return
        }
        _state.value = _state.value.copy(loading = true, error = null, info = "正在对账拉取…")
        viewModelScope.launch {
            try {
                // 网络部分内部已 IO；SAF 写缓存也在此离开主线程
                val s = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    syncer.pull { written, total ->
                        _state.update { it.copy(info = "同步中 $written/$total") }
                    }
                }
                loadEntries()
                _state.update {
                    it.copy(
                        loading = false,
                        info = "同步：更新 ${s.updated} · 删除 ${s.deleted} · 未变 ${s.unchanged}",
                    )
                }
            } catch (e: Exception) {
                loadEntries()
                _state.update {
                    it.copy(loading = false, info = null, error = "拉取失败，显示本地缓存：${e.message}")
                }
            }
        }
    }

    /** 从缓存 diary/ 目录构建日记流（文件名即日期，倒序）；顺带批量预解析附件 uri（滑动零 IPC） */
    fun loadEntries() {
        if (store.treeUri().isBlank()) return
        viewModelScope.launch {
            try {
                val entries = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val syncer = CacheSyncer(getApplication(), store)
                val list = syncer.listCache("diary")
                    .filter { DiaryParser.isDiary(it) }
                    .sortedDescending()
                    .mapNotNull { name ->
                        val content = syncer.readCache("diary/$name")?.toString(Charsets.UTF_8) ?: ""
                        DiaryParser.parse(name, content)
                    }
                com.dailynotes.app.data.AttachmentUriCache.putAll(syncer.urisUnderCache("attachments"))
                list
                }
                _state.update {
                    it.copy(
                        entries = entries,
                        dateSet = entries.map { d -> d.date }.toSet(),
                        entriesVersion = it.entriesVersion + 1,
                    )
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(error = "读取本地缓存失败：${e.message}") }
            }
        }
    }
}
