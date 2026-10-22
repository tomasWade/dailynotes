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

    /** 批量写同一目录：一次 listFiles 拿现有 {name→DocumentFile}（listFiles 结果自带 uri），
     *  免逐文件 findFile。write() 每文件 findFile 两次、每次都列全目录——1481 文件全量同步
     *  是 O(N²) 目录扫描，「几 MB 同步几分钟」的根因（2026-10-22 性能诊断）。
     *  批内逐个写，任一失败即抛（已写的留在盘上，调用方 checkpoint 语义兜底：未 checkpoint
     *  的批下轮重拉，粒度 = 批大小）。
     *  重名防御（R1-M1）：existing 是批开始的快照，僵尸/并发写可能在此期间已建同名文件，
     *  provider 对 createFile 同名会生成 "name (1)" 副本——发现名字不匹配时精确 findFile 一次
     *  自愈（此时文件已在，单次列举代价可接受），只发生在异常交错路径。 */
    fun writeBatch(dir: String, entries: List<Pair<String, ByteArray>>) {
        val dirFile = dirFor(dir.trim('/').split("/").filter { it.isNotBlank() })
        val existing = dirFile.listFiles().associateBy { it.name }
        for ((name, bytes) in entries) {
            val target = existing[name]?.takeIf { it.isFile }
                ?: dirFile.createFile("application/octet-stream", name)?.let { created ->
                    if (created.name == name) {
                        created
                    } else {
                        // provider 生成了 "name (1)" 重名副本（快照过期的交错写）——删掉孤儿再精确找回
                        created.delete()
                        dirFile.findFile(name)?.takeIf { it.isFile }
                            ?: throw IllegalStateException("无法创建文件: $dir/$name")
                    }
                }
                ?: throw IllegalStateException("无法创建文件: $dir/$name")
            resolver.openOutputStream(target.uri, "wt")?.use { it.write(bytes) }
                ?: throw IllegalStateException("无法写入: $dir/$name")
        }
    }

    /** 批量读：一次列目录建 name→DocumentFile 映射，再逐个 openInputStream。
     *  修复单读 O(N²)：read(path) 每次要列两次目录找路径（diary/ 1500 项时单次 ~0.5s，
     *  1481 篇逐读 ≈ 13 分钟，列表长时间空白）；本方法全目录只列一次，N 次打开。
     *  单条打开失败/读异常跳过（runCatching），该条不出现在结果里——调用方按缺失=空内容处理
     *  （条目仍在列表；点开走编辑器单读路径，那里失败会显式报错）。目录不存在返回空 Map。 */
    fun readBatch(dir: String, names: List<String>): Map<String, ByteArray> {
        val base = walkTo(dir) ?: return emptyMap()
        val byName = base.listFiles().associateBy { it.name }
        val out = mutableMapOf<String, ByteArray>()
        for (n in names) {
            val f = byName[n]?.takeIf { it.isFile } ?: continue
            val bytes = runCatching {
                resolver.openInputStream(f.uri)?.use { input ->
                    ByteArrayOutputStream().also { input.copyTo(it) }.toByteArray()
                }
            }.getOrNull()
            if (bytes != null) out[n] = bytes
        }
        return out
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
        // main 优先：bak 先写（writeState 顺序），中断会坏 bak 而 main 是上一轮完整版；
        // main 损坏（半写）时回退 bak。两道都不可解析 → null（空 state，全量重拉自愈）
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
