package com.dailynotes.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.dailynotes.app.data.CacheSyncer
import com.dailynotes.app.data.DiagLog
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
        // 服务器已配置但从未成功同步（新装机/升级换缓存目录）：空列表时引导先同步而非「写第一篇」
        val neverSynced: Boolean = false,
    )

    private val store = SettingsStore(app)
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    init {
        _state.value = _state.value.copy(neverSynced = store.serverUrl().isNotBlank() && !store.everSynced())
        refresh()
    }

    fun refresh() {
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

    /** 从缓存 diary/ 目录构建日记流。
     *  双阶段（v0.2.7 列表快照）：①快照命中立即渲染（冷启动秒出，免全量重读 1481 个文件）
     *  ②mtime 对账，只重读变化文件，更新快照。无快照时退化为原全量路径（一次性，之后秒出）。 */
    fun loadEntries() {
        viewModelScope.launch {
            try {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    val t0 = android.os.SystemClock.elapsedRealtime()
                    val syncer = CacheSyncer(getApplication(), store)
                    val snap = com.dailynotes.app.data.ListCache.load(getApplication(), "diary")
                    com.dailynotes.app.data.DiagLog.log("home", "loadEntries start: snap=${snap.size} entries, t=${android.os.SystemClock.elapsedRealtime() - t0}ms")

                    fun snapEntries(m: Map<String, com.dailynotes.app.data.ListCache.CachedEntry>): List<DiaryEntry> =
                        m.entries.mapNotNull { (name, e) ->
                            DiaryParser.parseDate(name)?.let { DiaryEntry(name, it, e.preview, e.firstImage, e.truncated) }
                        }.sortedByDescending { it.date }

                    fun publish(list: List<DiaryEntry>) {
                        _state.update {
                            it.copy(entries = list, dateSet = list.map { d -> d.date }.toSet(), entriesVersion = it.entriesVersion + 1)
                        }
                    }

                    // 阶段1：快照非空 → 立即出列表
                    val phase1List: List<DiaryEntry> = if (snap.isNotEmpty()) {
                        val l = snapEntries(snap); publish(l); l
                    } else emptyList()

                    // 阶段2：mtime 对账（File 世界本地 stat，无 IPC）
                    val listing = syncer.listWithMtimeCache("diary").filter { DiaryParser.isDiary(it.first) }
                    com.dailynotes.app.data.DiagLog.log("home", "phase2 listing=${listing.size} changed=${listing.count { (n, m) -> snap[n]?.mtime != m }} t=${android.os.SystemClock.elapsedRealtime() - t0}ms")
                    if (listing.isEmpty() && snap.isNotEmpty()) {
                        // 瞬时空目录（极端情况保快照跳过重建，防 pager 踢人，R10-M1）
                        return@withContext
                    }
                    val changed = listing.filter { (n, m) -> snap[n]?.mtime != m }.map { it.first }
                    if (snap.isNotEmpty() && changed.isEmpty() && snap.size == listing.size) {
                        return@withContext // 快照已最新，零文件读取
                    }
                    val contents = if (changed.isEmpty()) emptyMap() else syncer.readCacheBatch("diary", changed)
                    val newSnap = HashMap<String, com.dailynotes.app.data.ListCache.CachedEntry>(listing.size)
                    for ((n, m) in listing) {
                        val text = contents[n]?.toString(Charsets.UTF_8)
                        if (text != null) {
                            DiaryParser.parse(n, text)?.let {
                                newSnap[n] = com.dailynotes.app.data.ListCache.CachedEntry(m, it.preview, it.firstImage, it.truncated)
                            }
                        } else {
                            // mtime 未变沿用旧条目；mtime 变了但读取被跳过也回落旧条目——
                            // 刻意保留旧 mtime：下次对账重新检测重试（换成新 mtime 会把陈旧预览钉死，R10-L1）
                            snap[n]?.let { newSnap[n] = it }
                        }
                    }
                    com.dailynotes.app.data.ListCache.save(getApplication(), "diary", newSnap)
                    val finalList = snapEntries(newSnap)
                    // 与阶段1结果相同则不再发布（省 pager 因 entriesVersion 变化重读页，R10-L2）
                    if (snap.isEmpty() || finalList != phase1List) publish(finalList)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(error = "读取本地缓存失败：${e.message}") }
            }
        }
    }
}
