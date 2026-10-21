package com.dailynotes.app.data

import android.content.Context
import com.dailynotes.app.diary.DiaryParser
import com.dailynotes.sync.SyncCoordinator
import com.dailynotes.sync.WebDavSyncEngine

/**
 * B 档同步门面：SAF 缓存 + cache-state 对账 + WebDAV 推拉删，三端（主页/笔记/编辑器）共用。
 * 所有方法都可能抛异常，由调用方决定提示方式（需求 6：失败仅弹提示）。
 */
class CacheSyncer(context: Context, private val store: SettingsStore) {

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

    private fun coordinator(): SyncCoordinator {
        val base = (store.serverUrl().trimEnd('/') + "/" + store.rootPath().trim('/')).trimEnd('/')
        return SyncCoordinator(WebDavSyncEngine(base, store.username(), store.password()))
    }

    /** 打开/手动同步：etag 对账拉取，写缓存，更新 cache-state（含 lastSync） */
    suspend fun pull(): PullSummary {
        val state = CacheState.parse(cache.readState())
        val result = coordinator().pull(state.files)

        val files = state.files.toMutableMap()
        for (f in result.updated) {
            cache.write(f.path, f.bytes)
            files[f.path] = f.etag
        }
        for (d in result.deleted) {
            cache.delete(d)
            files.remove(d)
        }
        val now = System.currentTimeMillis()
        cache.writeState(CacheState.dump(files, now))
        return PullSummary(result.updated.size, result.deleted.size, result.unchangedCount, now)
    }

    /**
     * 保存推送（LWW）：先推正文引用的未同步附件，再推文件本体，更新 cache-state。
     * 注意调用方应已把 bytes 写入缓存（失败时本地仍有内容可看）。
     * 返回 false=未配置服务器（仅本地模式，不抛异常）。
     */
    suspend fun push(relPath: String, bytes: ByteArray, text: String): Boolean {
        cache.write(relPath, bytes)
        if (!serverConfigured()) return false

        val c = coordinator()
        val state = CacheState.parse(cache.readState())
        val files = state.files.toMutableMap()

        for (ref in attachmentRefs(text)) {
            if (files[ref] == null) {
                cache.read(ref)?.let { ab -> files[ref] = c.push(ref, ab) }
            }
        }
        files[relPath] = c.push(relPath, bytes)
        cache.writeState(CacheState.dump(files, state.lastSync))
        return true
    }

    /** 删除：远端删 + 缓存删 + state 移除；远端删失败抛异常（不落本地删除，防止下次拉回造成幽灵复活观感） */
    suspend fun delete(relPath: String) {
        val c = coordinator()
        c.delete(relPath)
        cache.delete(relPath)
        val state = CacheState.parse(cache.readState())
        val files = state.files.toMutableMap()
        files.remove(relPath)
        cache.writeState(CacheState.dump(files, state.lastSync))
    }

    private fun attachmentRefs(text: String): List<String> =
        Regex("""\]\(([^)]*attachments/[^)\s]+)\)""")
            .findAll(text)
            .map { DiaryParser.toRootRelative(it.groupValues[1]) }
            .distinct()
            .toList()
}
