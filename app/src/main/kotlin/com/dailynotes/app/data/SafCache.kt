package com.dailynotes.app.data

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import java.io.ByteArrayOutputStream

class SafCache(context: Context, treeUriString: String) {

    private val resolver = context.contentResolver
    private val root: DocumentFile =
        DocumentFile.fromTreeUri(context, Uri.parse(treeUriString))
            ?: throw IllegalArgumentException("无效的缓存文件夹 URI")

    private fun dirFor(segments: List<String>): DocumentFile {
        var cur = root
        for (seg in segments) {
            cur = cur.findFile(seg)?.let { if (it.isDirectory) it else null }
                ?: cur.createDirectory(seg)
                ?: throw IllegalStateException("无法创建目录: $seg")
        }
        return cur
    }

    fun write(path: String, bytes: ByteArray) {
        val segments = path.trim('/').split("/").filter { it.isNotBlank() }
        val name = segments.last()
        val dir = dirFor(segments.dropLast(1))
        val existing = dir.findFile(name)
        val target = existing?.takeIf { it.isFile } ?: dir.createFile("application/octet-stream", name)
            ?: throw IllegalStateException("无法创建文件: $path")
        resolver.openOutputStream(target.uri, "wt")?.use { it.write(bytes) }
            ?: throw IllegalStateException("无法写入: $path")
    }

    fun read(path: String): ByteArray? {
        val segments = path.trim('/').split("/").filter { it.isNotBlank() }
        val name = segments.last()
        var cur: DocumentFile = root
        for (seg in segments.dropLast(1)) {
            cur = cur.findFile(seg)?.takeIf { it.isDirectory } ?: return null
        }
        val file = cur.findFile(name)?.takeIf { it.isFile } ?: return null
        return resolver.openInputStream(file.uri)?.use { input ->
            ByteArrayOutputStream().also { input.copyTo(it) }.toByteArray()
        }
    }

    /** 平铺列出目录内文件名（不含子目录）；目录不存在返回空 */
    fun list(dir: String): List<String> {
        walkTo(dir)?.let { return it.listFiles().filter { f -> f.isFile }.mapNotNull { f -> f.name }.sorted() }
        return emptyList()
    }

    /** 递归列出目录下全部文件（含子目录），返回根相对路径 */
    fun listRecursive(dir: String): List<String> {
        val base = walkTo(dir) ?: return emptyList()
        val out = mutableListOf<String>()
        val prefix = dir.trim('/') + "/"
        fun walk(d: DocumentFile, p: String) {
            for (f in d.listFiles()) {
                val name = f.name ?: continue
                if (f.isDirectory) walk(f, "$p$name/")
                else out += "$p$name"
            }
        }
        walk(base, prefix)
        return out
    }

    /** 递归列出目录下全部文件的 path→Uri 映射。
     *  逐目录一次 listFiles（每个子目录一次 IPC），替代逐文件 findFile（后者每个文件都列目录，
     *  列表滑动卡顿根因）。整棵树耗时随子目录数线性增长——外层调用应配遍历级超时预算（见 CacheSyncer）。 */
    fun urisUnder(dir: String): Map<String, Uri> {
        val base = walkTo(dir) ?: return emptyMap()
        val out = mutableMapOf<String, Uri>()
        val prefix = dir.trim('/') + "/"
        fun walk(d: DocumentFile, p: String) {
            for (f in d.listFiles()) {
                val name = f.name ?: continue
                if (f.isDirectory) walk(f, "$p$name/")
                else if (f.isFile) out["$p$name"] = f.uri
            }
        }
        walk(base, prefix)
        return out
    }

    private fun walkTo(dir: String): DocumentFile? {
        var cur: DocumentFile = root
        for (seg in dir.trim('/').split("/").filter { it.isNotBlank() }) {
            cur = cur.findFile(seg)?.takeIf { it.isDirectory } ?: return null
        }
        return cur
    }

    /** 文件的 content Uri（Coil 加载用）；不存在返回 null */
    fun uri(path: String): Uri? {
        val segments = path.trim('/').split("/").filter { it.isNotBlank() }
        val name = segments.last()
        var cur: DocumentFile = root
        for (seg in segments.dropLast(1)) {
            cur = cur.findFile(seg)?.takeIf { it.isDirectory } ?: return null
        }
        return cur.findFile(name)?.takeIf { it.isFile }?.uri
    }

    fun delete(path: String) {
        val segments = path.trim('/').split("/").filter { it.isNotBlank() }
        val name = segments.last()
        var cur: DocumentFile = root
        for (seg in segments.dropLast(1)) {
            cur = cur.findFile(seg)?.takeIf { it.isDirectory } ?: return
        }
        cur.findFile(name)?.delete()
    }

    /** 双文件冗余：先写 bak 再写主文件，半写损坏只可能坏其一——
     *  主文件半断 → 读侧 JSON 校验失败 → 回退 bak（上一轮完整内容，最多丢最近一次 checkpoint）。
     *  全量同步期间 checkpoint 会把写次数放大几十倍，单文件 truncate+write 的损坏窗口不可接受。 */
    fun writeState(json: String) {
        val bytes = json.toByteArray(Charsets.UTF_8)
        write(STATE_BAK, bytes)
        write(STATE_MAIN, bytes)
    }

    fun readState(): String? {
        read(STATE_MAIN)?.toString(Charsets.UTF_8)?.let { if (isParsable(it)) return it }
        return read(STATE_BAK)?.toString(Charsets.UTF_8)?.takeIf { isParsable(it) }
    }

    private fun isParsable(json: String): Boolean =
        runCatching { org.json.JSONObject(json); true }.getOrDefault(false)

    private companion object {
        const val STATE_MAIN = ".dailynotes/cache-state.json"
        const val STATE_BAK = ".dailynotes/cache-state.json.bak"
    }
}
