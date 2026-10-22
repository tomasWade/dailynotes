package com.dailynotes.app.data

import android.net.Uri
import android.util.LruCache

/**
 * 文件 path → SAF Uri 的进程级缓存（线程安全）。
 * v0.2.6 起通用化：不只附件，diary/notes 全部文件都走这里（SafCache.read 等接入）——
 * 单文件读的 findFile 模式每次列全目录两次，1481 项目录下打开一篇 ~2.3s 的根因。
 * Uri.EMPTY 作负缓存哨兵（解析过但文件不存在），防止对缺失文件反复 IPC。
 * 失效契约（SafCache 各写路径维护）：write/writeBatch→put 新 uri；delete→remove；
 * CacheSyncer.pull 的 deleted→remove；换缓存文件夹→clear。
 * Uri 因外部改动失效时读侧 openInputStream 失败会回退 findFile 实时解析，自愈。
 * 已知可接受竞态（review 认定 theoretical，自愈路径完备）：
 * ① uri() 的 EMPTY 可能瞬间覆盖 pull 刚 put 的真实 Uri（毫秒窗口，下次 readBatch/putAll 回填即正）；
 * ② 换缓存文件夹时在途的旧树任务可能在 clear() 之后回填旧树 Uri（下次 pull 纠正）。
 * 注意「自愈」仅覆盖失效的**真实 Uri**（读侧 openInputStream 失败回退重解析）；
 * 负缓存 EMPTY 不自愈——外部（非本 App 写路径）新建的文件要等 readBatch 回填/写路径覆盖/进程重启才可见（R9-Note）。
 */
object AttachmentUriCache {

    // 1500+ diary + notes + attachments 全量热缓存（每条仅 String+Uri 引用，4096 条内存可忽略）
    private const val MAX_ENTRIES = 4096

    private val cache = object : LruCache<String, Uri>(MAX_ENTRIES) {
        override fun sizeOf(key: String, value: Uri) = 1
    }

    /** 返回 null=未缓存；Uri.EMPTY=缓存过但不存在 */
    fun get(path: String): Uri? = cache.get(path)

    fun put(path: String, uri: Uri?) {
        cache.put(path, uri ?: Uri.EMPTY)
    }

    fun putAll(map: Map<String, Uri>) {
        map.forEach { (k, v) -> cache.put(k, v) }
    }

    fun remove(path: String) {
        cache.remove(path)
    }

    /** 清空全部缓存（切换缓存文件夹后旧 Uri 全部失效） */
    fun clear() {
        cache.evictAll()
    }
}
