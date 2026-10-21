package com.dailynotes.app.data

import android.net.Uri
import android.util.LruCache

/**
 * 附件 path → SAF Uri 的进程级缓存（线程安全）。
 * Uri.EMPTY 作负缓存哨兵（解析过但文件不存在），防止对缺失文件反复 IPC。
 * 文件删除后调用 remove 失效。
 */
object AttachmentUriCache {

    private const val MAX_ENTRIES = 512

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
}
