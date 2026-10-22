package com.dailynotes.app.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.dailynotes.app.data.AttachmentUriCache
import com.dailynotes.app.data.CacheState
import com.dailynotes.app.data.CacheSyncer
import com.dailynotes.app.data.SafCache
import com.dailynotes.app.data.SettingsStore
import com.dailynotes.app.diary.DiaryParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
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
        // 附件回收站
        val scanning: Boolean = false,
        val orphans: List<String> = emptyList(),
        val cleanupInfo: String? = null,
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
            val (diaries, noteCount, state) = kotlinx.coroutines.withContext(Dispatchers.IO) {
                val cache = SafCache(getApplication(), store.treeUri())
                Triple(
                    cache.list("diary").filter { DiaryParser.isDiary(it) },
                    cache.list("notes").count { com.dailynotes.app.note.NoteParser.isNote(it) },
                    CacheState.parse(cache.readState()),
                )
            }
            // 事实以缓存目录为准（B 档全量缓存），cache-state 兜底附件计数
            val dates = diaries.mapNotNull { DiaryParser.parseDate(it) }.toSet()
            val attachments = state.files.keys.count { it.startsWith("attachments/") }
            _state.value = _state.value.copy(
                stats = Stats(
                    diaryCount = diaries.size,
                    streakDays = DiaryParser.streak(dates),
                    noteCount = noteCount,
                    attachmentCount = attachments,
                    lastSync = state.lastSync,
                ),
            )
        }
    }

    /** 附件回收站：扫描全部 md 引用，找出零引用的缓存附件 */
    fun scanOrphans() {
        if (store.treeUri().isBlank()) {
            _state.value = _state.value.copy(cleanupInfo = "请先到「设置」选择缓存文件夹")
            return
        }
        _state.value = _state.value.copy(scanning = true, cleanupInfo = null)
        viewModelScope.launch {
            try {
                val orphans = scanOrphansInternal()
                _state.value = _state.value.copy(
                    scanning = false,
                    orphans = orphans,
                    // 区分「没扫过」与「扫了没有」（R2-L5）；scanOrphans 进入时已置 null，此处无需保旧值
                    cleanupInfo = if (orphans.isEmpty()) "扫描完成，没有发现孤儿附件" else null,
                )
            } catch (e: Exception) {
                _state.value = _state.value.copy(scanning = false, cleanupInfo = "扫描失败：${e.message}")
            }
        }
    }

    private suspend fun scanOrphansInternal(): List<String> = withContext(Dispatchers.IO) {
        val cache = SafCache(getApplication(), store.treeUri())
        val syncer = CacheSyncer(getApplication(), store)
        val referenced = mutableSetOf<String>()
        for (dir in listOf("diary", "notes")) {
            // 批量读（v0.2.5 同型全扫）：旧逐文件 read 每次列全目录两次（O(N²)），
            // 1481 篇日记时进本页即卡 10 分钟级——与主页列表空白同病
            val names = syncer.listCache(dir)
            val contents = syncer.readCacheBatch(dir, names)
            for (name in names) {
                val text = contents[name]?.toString(Charsets.UTF_8) ?: continue
                referenced += DiaryParser.attachmentRefs(text)
            }
        }
        // 一次 IPC 预解析全部附件 Uri，清理页网格渲染全程零 SAF IPC（R1-M2）
        AttachmentUriCache.putAll(cache.urisUnder("attachments"))
        cache.listRecursive("attachments").filter { it !in referenced }
    }

    /** 删除所选孤儿附件：远端删 + 缓存删 + state 移除 + uri 缓存失效；单文件失败不影响其余 */
    fun deleteOrphans(paths: List<String>) {
        if (paths.isEmpty()) return
        if (store.treeUri().isBlank()) return
        val syncer = CacheSyncer(getApplication(), store)
        if (!syncer.serverConfigured()) {
            _state.value = _state.value.copy(
                scanning = false,
                cleanupInfo = "未配置服务器：清理需同时删除远端，请先在「设置」配置",
            )
            return
        }
        _state.value = _state.value.copy(scanning = true, cleanupInfo = null)
        viewModelScope.launch {
            var ok = 0
            var failed = 0
            for (p in paths) {
                try {
                    syncer.delete(p)
                    ok++
                } catch (_: Exception) {
                    failed++
                }
            }
            val rescan = runCatching { scanOrphansInternal() }.getOrDefault(emptyList())
            _state.value = _state.value.copy(
                scanning = false,
                orphans = rescan,
                cleanupInfo = "已删除 $ok 个" + if (failed > 0) "，失败 $failed 个（网络问题）" else "",
            )
            loadStats()
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
                val cache = SafCache(getApplication(), store.treeUri())
                val (count, readFailed) = withContext(Dispatchers.IO) {
                    val resolver = getApplication<Application>().contentResolver
                    var n = 0
                    var failed = 0
                    resolver.openOutputStream(uri, "wt")?.use { out ->
                        ZipOutputStream(out.buffered()).use { zip ->
                            // 批量读（v0.2.5 同型全扫）：旧逐文件 read 每次列全目录两次（O(N²)），
                            // 1481 篇导出同卡 10 分钟级。按父目录分组批量读；zip 条目集合不变，
                            // 同目录内顺序不变（跨子目录因 groupBy 连续化，zip 读取无感知）。
                            // 备份工件禁止静默缺条目：readBatch 吞掉的 IO 失败必须显式计数上报（review MEDIUM）
                            val syncer = CacheSyncer(getApplication(), store)
                            for (dir in listOf("diary", "notes", "attachments")) {
                                val rels = cache.listRecursive(dir)
                                for ((parent, group) in rels.groupBy { it.substringBeforeLast('/') }) {
                                    val contents = syncer.readCacheBatch(parent, group.map { it.substringAfterLast('/') })
                                    failed += group.size - contents.size
                                    for (rel in group) {
                                        val bytes = contents[rel.substringAfterLast('/')] ?: continue
                                        zip.putNextEntry(ZipEntry(rel))
                                        zip.write(bytes)
                                        zip.closeEntry()
                                        n++
                                    }
                                }
                            }
                            cache.readState()?.let { s ->
                                zip.putNextEntry(ZipEntry(".dailynotes/cache-state.json"))
                                zip.write(s.toByteArray(Charsets.UTF_8))
                                zip.closeEntry()
                            }
                        }
                    } ?: throw IllegalStateException("无法打开导出目标")
                    n to failed
                }
                _state.value = _state.value.copy(
                    loading = false,
                    info = if (readFailed > 0) {
                        "已导出 $count 个文件，⚠️ $readFailed 个读取失败未包含——请重试或检查存储"
                    } else {
                        "已导出 $count 个文件"
                    },
                )
            } catch (e: Exception) {
                _state.value = _state.value.copy(loading = false, error = "导出失败：${e.message}")
            }
        }
    }

    fun lastSyncLabel(): String {
        val ts = _state.value.stats.lastSync
        if (ts <= 0) return "从未"
        return DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").format(
            Instant.ofEpochMilli(ts).atZone(ZoneId.systemDefault())
        )
    }
}
