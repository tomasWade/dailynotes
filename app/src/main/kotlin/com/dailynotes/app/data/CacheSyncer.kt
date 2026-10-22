package com.dailynotes.app.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.withLock
import com.dailynotes.app.diary.DiaryParser
import com.dailynotes.sync.SyncCoordinator
import com.dailynotes.sync.WebDavSyncEngine

/** 应用层乐观锁冲突：远端 etag 与本地记录不一致（别处更新/已删除），保存被拒绝。
 *  copySaved=是否已把用户内容留副本到 <relPath>.conflict-local-<时间戳>.md */
class SyncConflictException(
    val relPath: String,
    val remoteDeleted: Boolean = false,
    val copySaved: Boolean = false,
) : Exception("远端已有变更：$relPath")

/**
 * B 档同步门面：SAF 缓存 + cache-state 对账 + WebDAV 推拉删，三端（主页/笔记/编辑器）共用。
 * 所有方法都可能抛异常，由调用方决定提示方式（需求 6：失败仅弹提示）。
 */
class CacheSyncer(context: Context, private val store: SettingsStore) {

    companion object {
        // cache-state.json 的 read-modify-write 全局串行锁：CacheSyncer 每次调用方都 new 实例，
        // 但 pager 相邻页可能并发 recover / pull 并发保存，交错 RMW 会丢 etag 记录或写坏 JSON
        // （损坏虽可自愈全量重拉，但丢记录会让乐观锁暂时失效退化为 LWW）
        private val stateMutex = kotlinx.coroutines.sync.Mutex()

        // SAF binder 调用无超时：DocumentsProvider 假死时协程会永久挂起并持有 stateMutex，
        // 之后所有同步排队假转圈（换机全量同步必现场景）。防护：
        // 1) 单次 SAF 操作 30s「放弃等待」（见 abandonOnTimeout：binder 不响应 interrupt，
        //    有效模式是 async 派发+轮询放弃等待——⚠️ 不要改回 withTimeoutOrNull{await()}，有实锤 TCE race）
        // 2) pull 每 50 个文件落一次 cache-state + 中止前补终 checkpoint（断点续传，进度永不丢）
        // 3) 网络阶段不设总预算（OkHttp 单请求 10/30/30s 已兜底，总预算会把慢网全量卡死在零进度）
        private const val SAF_OP_TIMEOUT_MS = 30_000L

        /** 递归遍历专用预算（R6-L5）：urisUnder 实为逐目录 listFiles（每目录一次 IPC），
         *  大附件树（多个月桶）整体耗时可观，慢 provider 上 30s 会误报失败 */
        private const val SAF_TRAVERSAL_TIMEOUT_MS = 120_000L
        private const val CHECKPOINT_EVERY = 50

        // 放弃等待的僵尸协程归宿：SAF 调用（若）最终返回后自然结束回池。
        // SupervisorJob + runCatching：僵尸的异常不会炸 scope 也不会走未捕获处理器。
        // ⚠️ 边界：每次超时留 1 个僵尸占 1 个 IO 线程（池上限 64），手动重试场景安全；
        // 禁止在此架构上直接加自动重试/定时同步——provider 持续假死会耗尽 IO 池冻结全 app。
        // ⚠️ 交错窗口（R6-L4）：超时的僵尸写若最终完成，可能晚于手动重试的写落盘 →
        // 缓存留旧字节而 state 记新 etag，内容陈旧到下次远端变更为止（无损坏，接受并文档化）。
        private val safScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    data class PullSummary(
        val updated: Int,
        val deleted: Int,
        val unchanged: Int,
        val lastSync: Long,
    )

    val treeUri: String get() = store.treeUri()
    val cache: SafCache by lazy { SafCache(context, store.treeUri()) }

    fun serverConfigured(): Boolean =
        store.serverUrl().isNotBlank() && store.username().isNotBlank()

    private fun engine(): com.dailynotes.sync.WebDavSyncEngine {
        val base = (store.serverUrl().trimEnd('/') + "/" + store.rootPath().trim('/')).trimEnd('/')
        return com.dailynotes.sync.WebDavSyncEngine(base, store.username(), store.password())
    }

    private fun coordinator(): SyncCoordinator = SyncCoordinator(engine())

    /** SAF 操作「放弃等待」：async 派发后轮询完成状态，超时不再等（也不 interrupt——
     *  binder 不响应中断，R1 实验证实 runInterruptible/withContext 都必须等 block 结束）。
     *  超时抛 IllegalStateException（刻意不用 CancellationException，避免被上层 catch(Exception)
     *  误吞且不污染结构化取消）；僵尸协程留 safScope 自生自灭，锁必释放（delay 可取消，
     *  外层取消从 delay 传出，withLock 的 finally 释放锁）。
     *
     *  ⚠️ 刻意不用 withTimeoutOrNull{ deferred.await() }（2026-10-22 实锤）：其恢复链存在
     *  TimeoutCancellationException 归属 race——block 数十 ms 内成功完成时，恢复路径仍可能被
     *  误取消/误抛「超时」异常（实测：await 已返回 ok，300ms 后协程仍 catch 到超时 ISE，
     *  栈穿 abandonOnTimeout$1/ScopeCoroutine.afterResume），成功的调用被 race 成假超时，
     *  导致 pull 首步 safeReadState 永远失败。轮询版行为完全透明，等价且无此雷。
     *  首轮立即检查 + 递增 poll（10→200ms）避免固定 50ms 下限；isCompleted 后走 await()
     *  （已完成时立即返回，无 race——race 在 withTimeout 不在 await）。 */
    private suspend fun <T> abandonOnTimeout(timeoutMs: Long, what: String, block: () -> T): T {
        val deferred = safScope.async { runCatching { block() } }
        val deadline = android.os.SystemClock.elapsedRealtime() + timeoutMs
        var poll = 10L
        while (!deferred.isCompleted) {
            if (android.os.SystemClock.elapsedRealtime() >= deadline) {
                throw IllegalStateException("$what 超时（${timeoutMs / 1000}s），SAF 存储无响应")
            }
            kotlinx.coroutines.delay(poll)
            poll = (poll * 2).coerceAtMost(200L)
        }
        return deferred.await().getOrThrow()
    }

    private suspend fun safeWrite(path: String, bytes: ByteArray) =
        abandonOnTimeout(SAF_OP_TIMEOUT_MS, "写入本地缓存 $path") { cache.write(path, bytes) }

    /** 批量安全写（pull 全量路径用）：一次 listFiles 直查 uri，免逐文件全目录扫描。
     *  预算按条目数缩放（30s 基数 + 每条目 1s 余量）：一批 ≈100 次 binder 操作，
     *  固定 30s 会让慢而未死的 provider（>300ms/文件）从「慢成功」变「永久超时」 */
    private suspend fun safeWriteBatch(dir: String, entries: List<Pair<String, ByteArray>>) =
        abandonOnTimeout(SAF_OP_TIMEOUT_MS + entries.size * 1_000L, "批量写入本地缓存 $dir ×${entries.size}") {
            cache.writeBatch(dir, entries)
        }

    private suspend fun safeDelete(path: String) =
        abandonOnTimeout(SAF_OP_TIMEOUT_MS, "删除本地缓存 $path") { cache.delete(path) }

    private suspend fun safeRead(path: String): ByteArray? =
        abandonOnTimeout(SAF_OP_TIMEOUT_MS, "读取本地缓存 $path") { cache.read(path) }

    /** 批量安全读（列表构建路径用，修复 loadEntries 单读 O(N²)：1500 篇逐读 13 分钟 → 秒级）。
     *  预算按条目缩放并封顶（基数 30s + 1s/条，上限 300s）：正常 N 次 open ≈ 毫秒级/条，
     *  慢而未死的 provider 也有余量；超时走 abandon 语义（放弃等待、释放锁）。
     *  已接受的 tradeoff（review F2）：>180ms/条 的极慢 provider 会从「慢成功」变「超时报错」——
     *  5 分钟级列表加载本身已是坏 UX，报错+手动重试优于无界等待。 */
    suspend fun readCacheBatch(dir: String, names: List<String>): Map<String, ByteArray> =
        abandonOnTimeout(
            minOf(SAF_OP_TIMEOUT_MS + names.size * 1_000L, 300_000L),
            "批量读取本地缓存 $dir ×${names.size}",
        ) { cache.readBatch(dir, names) }

    /** 公开的安全读（编辑器/阅读页入口用）：裸 cache.read 在 provider 假死时会永久挂起，
     *  绕过本类全部超时防护（R2-M1） */
    suspend fun readCache(path: String): ByteArray? = safeRead(path)

    /** 安全写（插图等缓存写入用，R4 附带） */
    suspend fun writeCache(path: String, bytes: ByteArray) = safeWrite(path, bytes)

    /** 安全列目录（列表构建用，R3-M1） */
    suspend fun listCache(dir: String): List<String> =
        abandonOnTimeout(SAF_OP_TIMEOUT_MS, "列目录 $dir") { cache.list(dir) }

    /** 安全批量解析附件 Uri（R3-M1）。注意 urisUnder 是逐目录 listFiles（非单次 IPC），
     *  故用放大的遍历预算（R6-L5） */
    suspend fun urisUnderCache(dir: String): Map<String, android.net.Uri> =
        abandonOnTimeout(SAF_TRAVERSAL_TIMEOUT_MS, "解析附件目录 $dir") { cache.urisUnder(dir) }

    private suspend fun safeReadState(): String? =
        abandonOnTimeout(SAF_OP_TIMEOUT_MS, "读取同步进度") { cache.readState() }

    /** cache-state 落盘（checkpoint 与最终提交共用），超时视为本轮失败 */
    private suspend fun checkpointState(files: Map<String, String>, lastSync: Long) {
        abandonOnTimeout(SAF_OP_TIMEOUT_MS, "保存同步进度") {
            cache.writeState(CacheState.dump(files, lastSync))
        }
    }

    /** 打开/手动同步：etag 对账拉取，写缓存，更新 cache-state（含 lastSync）。
     *  中断（任何异常）时已过 checkpoint 的文件不会重拉——下轮 etag 对账直接跳过。
     *  [onProgress] 每写满 CHECKPOINT_EVERY 个文件回调 (written, total)，用于 UI 进度「194/1500」。 */
    suspend fun pull(
        onProgress: ((written: Int, total: Int) -> Unit)? = null,
    ): PullSummary = stateMutex.withLock {
        // 网络对账+下载（OkHttp 单请求超时兜底，不设总预算：慢网全量会被总预算卡死在零进度）
        val state = CacheState.parse(safeReadState())
        val result = coordinator().pull(state.files)
        if (result.updated.isNotEmpty()) onProgress?.invoke(0, result.updated.size)

        val files = state.files.toMutableMap()
        var written = 0
        var lastCheckpointed = 0
        try {
            // 按目录分批批量写：writeBatch 一次 listFiles 拿全目录 {name→uri}，
            // 免旧逐文件 write 的每文件两次全目录扫描（实测约 100 倍提速；批粒度 listFiles，
            // 严格复杂度仍是 O(N²/批量)，数据量级下够用。2026-10-22 性能诊断）。
            // 批大小=CHECKPOINT_EVERY，checkpoint/断点/超时语义与旧逐文件版一致：
            // 批内中断 → 本批不记 etag → 下轮重拉 ≤CHECKPOINT_EVERY 个。
            // 无斜杠路径防御（R8-L1）：当前 TOP_DIRS 产物必带 '/'，此分支理论不可达；
            // 若未来列表逻辑变化，substringBeforeLast('/') 会把文件名当目录建出 file.md/file.md，
            // 故此类条目降级走单文件写（其 etag 进 files map，随后续批 checkpoint/补终一并落盘）。
            val (rootFiles, dirFiles) = result.updated.partition { !it.path.contains('/') }
            for (f in rootFiles) {
                safeWrite(f.path, f.bytes)
                files[f.path] = f.etag
                written++
            }
            val byDir = dirFiles.groupBy { it.path.substringBeforeLast('/') }
            for ((dir, group) in byDir) {
                for (batch in group.chunked(CHECKPOINT_EVERY)) {
                    safeWriteBatch(dir, batch.map { it.path.substringAfterLast('/') to it.bytes })
                    batch.forEach { files[it.path] = it.etag }
                    written += batch.size
                    checkpointState(files.toMap(), state.lastSync) // lastSync 保旧值：本轮未完成不算"同步过"
                    lastCheckpointed = written
                    onProgress?.invoke(written, result.updated.size)
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // 中止前补终 checkpoint（R1-H3/R6-L1）：written > lastCheckpointed 只在
            // 「批已写成功、批后 checkpoint 失败」时成立（批写失败时 written 未推进），
            // 此时 files 已含该批 etag，补终成功即进度零丢失。
            // 批内写失败（超时/SAF 错）：本批不记 etag，下轮重拉 ≤CHECKPOINT_EVERY 个，不丢数据。
            // catch Exception（rethrow CE）而非只 ISE：SecurityException 等 SAF 错误同样需要补终（R6-L2）。
            var note = "（已保存 $written/${result.updated.size}，重新点同步可从断点继续）"
            if (written > lastCheckpointed) {
                val saved = runCatching { checkpointState(files.toMap(), state.lastSync) }.isSuccess
                if (!saved) note = "（进度记录保存失败，重试时最多重下 $CHECKPOINT_EVERY 个文件）"
            }
            throw IllegalStateException("${e.message}$note", e)
        }
        for (d in result.deleted) {
            safeDelete(d)
            files.remove(d)
            // Uri 缓存失效由 SafCache.delete 内部维护（R9-L2 去冗余）
        }
        val now = System.currentTimeMillis()
        try {
            checkpointState(files, now)
        } catch (e: IllegalStateException) {
            // 内容已全部落缓存，只是进度文件写失败：下轮对账最多重下最后一批（R2-L1）
            throw IllegalStateException(
                "内容已全部同步到本地，仅进度记录保存失败——再点一次同步即可补记进度", e,
            )
        }
        PullSummary(result.updated.size, result.deleted.size, result.unchangedCount, now)
    }

    /**
     * 保存推送：先推正文引用的未同步附件，再推文件本体，更新 cache-state。
     * 应用层乐观锁（设计 §5.3 M0 结论）：PUT 前比对远端 etag 与 state 记录，
     * 不一致 = 别处已更新 → 抛 [SyncConflictException]，不覆盖（防旧内容 LWW 压掉新内容）。
     * 注意调用方应已把 bytes 写入缓存（失败时本地仍有内容可看）。
     * 返回 false=未配置服务器（仅本地模式，不抛异常）。
     */
    suspend fun push(relPath: String, bytes: ByteArray, text: String): Boolean = stateMutex.withLock {
        safeWrite(relPath, bytes)
        if (!serverConfigured()) return@withLock false

        val c = coordinator()
        val state = CacheState.parse(safeReadState())
        val files = state.files.toMutableMap()

        // 乐观锁检查（已同步过的文件才查；新文件跳过）。
        // remote != known 含两种情况：etag 不同（别处更新过）/ 空（远端已被别处删除）——都拒绝覆盖。
        // etagOf 对服务器错误 fail-closed 抛异常（不会静默降级 LWW）。
        val knownEtag = files[relPath]
        if (knownEtag != null) {
            val remote = c.engineEtag(relPath)
            if (remote != knownEtag) {
                // 用户输入留副本（仅本地缓存）：否则提示"去同步"后 pull 会覆盖缓存+discard 清编辑器，输入三处全丢
                val stamp = java.time.LocalDateTime.now()
                    .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss"))
                val ext = relPath.substringAfterLast('.', "md")
                val stem = relPath.removeSuffix(".$ext")
                val copyPath = "$stem.conflict-local-$stamp.$ext"
                // 副本写失败不致命（与超时一样按"未留副本"处理），故吞超时
                val copySaved = runCatching {
                    abandonOnTimeout(SAF_OP_TIMEOUT_MS, "写冲突副本") { cache.write(copyPath, bytes) }
                }.isSuccess
                throw SyncConflictException(relPath, remote.isBlank(), copySaved)
            }
        }

        for (ref in DiaryParser.attachmentRefs(text)) {
            if (files[ref] == null) {
                safeRead(ref)?.let { ab ->
                    val e = c.push(ref, ab)
                    if (e.isNotBlank()) files[ref] = e
                }
            }
        }
        val newEtag = c.push(relPath, bytes)
        if (newEtag.isNotBlank()) {
            files[relPath] = newEtag
        } else {
            // PUT 成功但没拿到 etag：移除旧记录（旧 etag 已失效，留着会假冲突），
            // 下次保存跳过锁检查、pull 对账自愈
            files.remove(relPath)
        }
        checkpointState(files, state.lastSync)
        return true
    }

    /** 删除：远端删 + 缓存删 + state 移除；远端删失败抛异常（不落本地删除，防止下次拉回造成幽灵复活观感） */
    suspend fun delete(relPath: String) = stateMutex.withLock {
        val c = coordinator()
        c.delete(relPath)
        safeDelete(relPath)
        val state = CacheState.parse(safeReadState())
        val files = state.files.toMutableMap()
        files.remove(relPath)
        checkpointState(files, state.lastSync)
    }

    /** state 是否有该文件的远端记录（用于 load 判定「缓存缺失是否危险」）。
     *  状态不可读（超时/SAF 错误）时返回 true（fail-closed，R6-L3）：无法证明远端没有记录
     *  就按危险处理 → loadFailed 禁保存。fail-open 的缝隙：readCache 正常返回 null（文件真缺）
     *  后 provider 恰在本次调用挂死，空白编辑器会误开保存绿灯，空内容覆盖远端。 */
    suspend fun hasRemoteRecord(relPath: String): Boolean = try {
        CacheState.parse(safeReadState()).files.containsKey(relPath)
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (_: Exception) {
        true
    }

    /**
     * 缓存缺失恢复：state 有 etag 记录（远端应有此文件）但本地缓存文件没了
     * （典型：用户手动删了缓存目录里的文件）→ 从远端拉回写缓存。
     * 返回 null = state 无记录 / 未配服务器 / 拉取失败（按新文件处理）。
     * 远端 404（其实已被别处删除）时顺带清掉 state 记录，让后续保存走"新建"语义。
     * 防连锁坑：不恢复的话编辑器显示空白，误保存将用空内容 LWW 覆盖远端。
     */
    suspend fun recover(relPath: String): ByteArray? = stateMutex.withLock {
        if (!serverConfigured()) return@withLock null
        val state = CacheState.parse(safeReadState())
        if (!state.files.containsKey(relPath)) return@withLock null
        val files = state.files.toMutableMap()
        try {
            val (bytes, etag) = engine().get(relPath)
            safeWrite(relPath, bytes)
            files[relPath] = etag
            checkpointState(files, state.lastSync)
            bytes
        } catch (e: com.dailynotes.sync.SyncException) {
            if (e.statusCode == 404) {
                // 远端已无此文件（别处删除后本地缓存也丢）：清记录，避免空白误保存复活空壳
                files.remove(relPath)
                checkpointState(files, state.lastSync)
            }
            null
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e   // 取消必须放行，不能吞（L5）
        } catch (_: Exception) {
            null   // 网络问题/SAF 超时：保留记录，下次打开再试恢复
        }
    }
}
