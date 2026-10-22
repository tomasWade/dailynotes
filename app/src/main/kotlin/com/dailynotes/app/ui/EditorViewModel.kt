package com.dailynotes.app.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.dailynotes.app.data.DiagLog
import com.dailynotes.app.data.CacheSyncer
import com.dailynotes.app.data.SettingsStore
import com.dailynotes.app.diary.DiaryParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class EditorViewModel(app: Application) : AndroidViewModel(app) {

    data class UiState(
        val relPath: String? = null,      // 完整相对路径，如 diary/2026-10-21.md 或 notes/xxx.md
        val text: String = "",
        val loading: Boolean = false,
        val saving: Boolean = false,
        val error: String? = null,
        val savedOk: Boolean = false,
        val initialPreview: Boolean? = null,   // load 完成：有内容=true（进预览态），空白=false（进编辑态）
        val loadFailed: Boolean = false,       // 读取彻底失败：禁用保存，防空白覆盖远端
    )

    private val store = SettingsStore(app)
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    /** 本次编辑会话插入的附件（根相对路径）。保存成功即清空；退出时未引用的删缓存防孤儿 */
    private val pendingAttachments = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    fun load(relPath: String) {
        if (_state.value.relPath == relPath) return
        _state.value = UiState(relPath = relPath, loading = true)
        viewModelScope.launch {
            val syncer = CacheSyncer(getApplication(), store)
            var readFailed = false
            var remoteShouldHave = false
            val t0 = android.os.SystemClock.elapsedRealtime()
            val text: String? = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                // read 失败不应连带跳过 recover（否则静默空白 → 误保存覆盖远端），各自独立捕获
                val t1 = android.os.SystemClock.elapsedRealtime()
                val fromCache = runCatching { syncer.readCache(relPath)?.toString(Charsets.UTF_8) }
                    .onFailure { readFailed = true; DiagLog.log("editor", "readCache throw: ${it.javaClass.simpleName}: ${it.message}") }
                    .getOrNull()
                val tRead = android.os.SystemClock.elapsedRealtime() - t1
                DiagLog.log("editor", "load[$relPath] readCache ${tRead}ms -> ${if (fromCache == null) "null" else "${fromCache.length}ch"}")
                var tRecover = -1L
                val t = fromCache ?: runCatching {
                    val t2 = android.os.SystemClock.elapsedRealtime()
                    val r = syncer.recover(relPath)?.toString(Charsets.UTF_8)
                    tRecover = android.os.SystemClock.elapsedRealtime() - t2
                    r
                }.getOrNull()
                if (tRecover >= 0) DiagLog.log("editor", "load[$relPath] recover ${tRecover}ms -> ${if (t == null) "null" else "${t.length}ch"}")
                // 缓存与恢复都没拿到，但远端可能已有此文件 → 危险空白：
                // ① state 显示远端应有（如断网）② 服务器已配置但从未成功同步过（升级换目录/新装机，
                //    cache-state 为空时 hasRemoteRecord 恒 false，离线保存会无锁 LWW 覆盖远端，R5-Issue1）
                if (t == null) {
                    val t3 = android.os.SystemClock.elapsedRealtime()
                    val neverSynced = syncer.serverConfigured() && !store.everSynced()
                    remoteShouldHave = syncer.hasRemoteRecord(relPath) || neverSynced
                    DiagLog.log("editor", "load[$relPath] hasRemoteRecord ${android.os.SystemClock.elapsedRealtime() - t3}ms -> $remoteShouldHave${if (neverSynced) " (含 neverSynced 收紧)" else ""}")
                }
                t
            }
            DiagLog.log(
                "editor",
                "load[$relPath] done ${android.os.SystemClock.elapsedRealtime() - t0}ms readFailed=$readFailed remoteShouldHave=$remoteShouldHave initialPreview=${!text.isNullOrBlank()}",
            )
            // 读取彻底失败判定（只在「真的没拿到内容」时报错）：
            // ① read 抛异常且 recover 也没拿到 ② 缓存缺失 + 远端应有但 recover 失败（断网/从未同步）
            // 注意：read 抛异常但 recover 成功（text!=null）→ 正常展示拉回的内容，不进错误态
            if ((readFailed && text == null) || remoteShouldHave) {
                _state.value = UiState(
                    relPath,
                    loadFailed = true,
                    error = if (store.everSynced()) {
                        "内容读取失败（缓存缺失且未能从服务器恢复，可能断网）。已禁用保存以防覆盖远端；" +
                            "请恢复网络后在主页点「同步」后重试。"
                    } else {
                        "本地缓存为空且尚未完成过同步，无法确认服务器上是否已有这篇内容。" +
                            "已禁用保存以防覆盖；请先在主页点「同步」完成首次同步后再编辑。"
                    },
                )
                return@launch
            }
            _state.value = UiState(
                relPath = relPath,
                text = text ?: "",
                initialPreview = !text.isNullOrBlank(),
            )
        }
    }

    /**
     * 退出编辑器时调用：清空状态，使下次进入同一文件会重新 load（拿到最新缓存/远端内容）。
     * 旋转屏幕不走此路径（onBack 才调），未保存文本在旋转后仍保留。
     * 同时清掉本次会话插入但最终正文未引用的孤儿附件（仅在本地缓存，删之安全）。
     */
    fun discard() {
        cleanupOrphanAttachments(_state.value.text)
        _state.value = UiState()
    }

    private fun cleanupOrphanAttachments(finalText: String) {
        if (pendingAttachments.isEmpty()) return
        val referenced = DiaryParser.attachmentRefs(finalText).toSet()
        val toDelete = pendingAttachments.filter { it !in referenced }
        if (toDelete.isNotEmpty()) {
            runCatching {
                val cache = com.dailynotes.app.data.FileCache(getApplication())
                toDelete.forEach { cache.delete(it) }
            }
        }
        pendingAttachments.clear()
    }

    override fun onCleared() {
        // Activity 被杀时同样清理（同步删少量文件，毫秒级）
        cleanupOrphanAttachments(_state.value.text)
        super.onCleared()
    }

    fun updateText(t: String) {
        _state.value = _state.value.copy(text = t, savedOk = false)
    }

    /** 从相册选图后调用：复制进缓存 attachments/YYYY-MM/ 并在正文末尾插入引用 */
    fun insertImage(source: Uri) {
        viewModelScope.launch {
            try {
                val syncer = CacheSyncer(getApplication(), store)
                val rootRel = withContext(Dispatchers.IO) {
                    val resolver = getApplication<Application>().contentResolver
                    val mime = resolver.getType(source) ?: "image/jpeg"
                    val ext = when (mime) {
                        "image/png" -> "png"
                        "image/webp" -> "webp"
                        "image/gif" -> "gif"
                        "image/heic" -> "heic"
                        else -> "jpg"
                    }
                    val bytes = resolver.openInputStream(source)?.use { it.readBytes() }
                        ?: throw IllegalStateException("无法读取所选图片")
                    val now = LocalDateTime.now()
                    val bucket = now.format(DateTimeFormatter.ofPattern("yyyy-MM"))
                    val stamp = now.format(DateTimeFormatter.ofPattern("HHmmss"))
                    val rand = List(4) { "0123456789abcdef".random() }.joinToString("")
                    val rel = "attachments/$bucket/${stamp}_$rand.$ext"
                    syncer.writeCache(rel, bytes)
                    pendingAttachments.add(rel)
                    rel
                }
                _state.value = _state.value.copy(text = _state.value.text + "\n\n![](../$rootRel)")
            } catch (e: Exception) {
                _state.value = _state.value.copy(error = "插图失败：${e.message}")
            }
        }
    }

    /** 保存：写缓存 → 推未同步附件 → 推本体 → 更新 cache-state。失败仅提示（需求 6） */
    fun save(onSaved: () -> Unit = {}) {
        val relPath = _state.value.relPath ?: return
        val text = _state.value.text
        _state.value = _state.value.copy(saving = true, error = null, savedOk = false)
        viewModelScope.launch {
            try {
                val syncer = CacheSyncer(getApplication(), store)
                val pushed = syncer.push(relPath, text.toByteArray(Charsets.UTF_8), text)
                // 成功（含仅本地模式）：正文已入库、引用成立，不再是孤儿候选
                pendingAttachments.clear()
                _state.value = if (pushed) {
                    _state.value.copy(saving = false, savedOk = true)
                } else {
                    _state.value.copy(saving = false, error = "未配置服务器，仅写入了本地缓存")
                }
                onSaved()
            } catch (e: com.dailynotes.app.data.SyncConflictException) {
                val reason = if (e.remoteDeleted) "远端此文件已被删除" else "远端已有更新版本"
                val copyNote = if (e.copySaved) {
                    "你的内容已留副本（缓存目录下 *.conflict-local-*.md）；如需继续编辑请先复制文本，再返回点「同步」。"
                } else {
                    "本地副本写入失败！如需保留本次内容，请立即全选复制文本，再返回点「同步」。"
                }
                _state.value = _state.value.copy(
                    saving = false,
                    error = "$reason，本次保存未覆盖。$copyNote",
                )
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    saving = false,
                    error = "同步失败：${e.message}（内容仍在编辑器，可再点保存重试）",
                )
            }
        }
    }
}
