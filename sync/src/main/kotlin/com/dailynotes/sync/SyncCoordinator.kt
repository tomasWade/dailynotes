package com.dailynotes.sync

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class RemoteFile(
    val path: String,
    val etag: Etag,
    val bytes: ByteArray,
)

data class PullResult(
    val updated: List<RemoteFile>,
    val deleted: List<String>,
    val unchangedCount: Int,
)

class SyncCoordinator(
    private val engine: SyncEngine,
    private val topDirs: List<String> = SyncEngine.TOP_DIRS,
) {

    suspend fun pull(known: Map<String, Etag>): PullResult = withContext(Dispatchers.IO) {
        val remote = listAllFiles()
        val updated = mutableListOf<RemoteFile>()
        var unchanged = 0
        for ((path, etag) in remote) {
            if (known[path] == etag) {
                unchanged++
            } else {
                val (bytes, getEtag) = engine.get(path)
                updated.add(RemoteFile(path, getEtag.ifBlank { etag }, bytes))
            }
        }
        val deleted = known.keys.filter { it !in remote }
        PullResult(updated, deleted, unchanged)
    }

    /** B 档推送：无条件 PUT（LWW），返回新 etag 供调用方记入 cache-state */
    suspend fun push(path: String, bytes: ByteArray): Etag = withContext(Dispatchers.IO) {
        engine.put(path, bytes)
    }

    /** B 档删除：远端删除（幂等，404 也算成功）；本地缓存与 state 由调用方处理 */
    suspend fun delete(path: String) = withContext(Dispatchers.IO) {
        engine.delete(path)
    }

    /** 单文件远端 etag（应用层乐观锁用）；不存在/不支持返回 "" */
    suspend fun engineEtag(path: String): Etag = withContext(Dispatchers.IO) {
        engine.etagOf(path)
    }

    private suspend fun listAllFiles(): Map<String, Etag> {
        val all = mutableMapOf<String, Etag>()
        for (top in topDirs) {
            engine.mkdir(top)
            collectDir(top, all)
        }
        return all
    }

    private suspend fun collectDir(dir: String, out: MutableMap<String, Etag>) {
        for ((path, etag) in engine.list(dir)) {
            out[path] = etag
        }
        val subDirs = engine.listDirs(dir)
        for (sub in subDirs) {
            collectDir(sub, out)
        }
    }
}
