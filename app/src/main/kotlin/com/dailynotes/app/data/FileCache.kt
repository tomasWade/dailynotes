package com.dailynotes.app.data

import android.content.Context
import java.io.File

/**
 * 本地缓存（v0.3.0 起）：App 外部私有目录 + java.io.File 直读，替代 SAF 实现。
 *
 * 为什么放弃 SAF 自定义目录（2026-10-23 决策）：SAF 无「按路径打开」API——寻址 = 列目录+比名
 * （真机 1481 项目录下冷读一篇 7.6s，1+3N 次 binder），为此维护的 path→uri 索引缓存
 * （AttachmentUriCache 内存+持久化）+ 批量读写 + 单 query 列举共 ~800 行防御代码。
 * File API 世界寻址天然 O(1)，上述全部删除。
 *
 * 代价（用户已接受）：手机文件管理器不可见（USB 连电脑 MTP 仍可见 Android/data 目录）；
 * 卸载 App 即清空本地——服务器是唯一事实源+每日快照，重装登录全量拉回即恢复。
 *
 * 目录结构（与 SAF 版完全一致，服务器/导出/导入零变化）：
 *   getExternalFilesDir("data")/diary|notes|attachments/.dailynotes/cache-state.json
 */
class FileCache(context: Context) {

    val root: File = File(context.getExternalFilesDir("data") ?: context.filesDir, "").apply { mkdirs() }

    private fun f(path: String): File {
        val p = path.trim('/')
        // 路径穿越守卫（R1-L3）：路径源自服务器列表/正文引用，防 ".." 逃出 root
        require(!p.split('/').any { it == ".." }) { "非法路径: $path" }
        return File(root, p)
    }

    /** 读文件；不存在返回 null */
    fun read(path: String): ByteArray? = f(path).takeIf { it.isFile }?.readBytes()

    /** 写文件（自动建父目录） */
    fun write(path: String, bytes: ByteArray) {
        val file = f(path)
        file.parentFile?.mkdirs()
        file.writeBytes(bytes)
    }

    /** 删除文件；不存在为 no-op（幂等） */
    fun delete(path: String) {
        f(path).delete()
    }

    /** 文件句柄（图片加载用）；不存在返回 null */
    fun file(path: String): File? = f(path).takeIf { it.isFile }

    /** 平铺列出目录内文件名（不含子目录）；目录不存在返回空 */
    fun list(dir: String): List<String> =
        f(dir).takeIf { it.isDirectory }?.listFiles()?.filter { it.isFile }?.map { it.name } ?: emptyList()

    /** 递归列出目录下全部文件（含子目录），返回根相对路径 */
    fun listRecursive(dir: String): List<String> {
        val base = f(dir).takeIf { it.isDirectory } ?: return emptyList()
        val prefix = dir.trim('/').let { if (it.isEmpty()) "" else "$it/" }
        val out = mutableListOf<String>()
        fun walk(d: File, p: String) {
            d.listFiles()?.forEach { fd ->
                if (fd.isDirectory) walk(fd, "$p${fd.name}/")
                else out.add("$p${fd.name}")
            }
        }
        walk(base, prefix)
        return out
    }

    /** 列目录带 mtime（列表快照对账用）。本地 stat 无 IPC。 */
    fun listWithMtime(dir: String): List<Pair<String, Long>> =
        f(dir).takeIf { it.isDirectory }?.listFiles()?.filter { it.isFile }
            ?.map { it.name to it.lastModified() } ?: emptyList()

    /** 批量读（SAF 时代为消除逐文件寻址 O(N²) 而设；File 世界逐读即 O(1)，
     *  保留接口仅为调用方兼容）。单条失败跳过，不出现在结果里。 */
    fun readBatch(dir: String, names: List<String>): Map<String, ByteArray> {
        val base = f(dir).takeIf { it.isDirectory } ?: return emptyMap()
        val out = mutableMapOf<String, ByteArray>()
        for (n in names) {
            val fd = File(base, n).takeIf { it.isFile } ?: continue
            runCatching { out[n] = fd.readBytes() }
        }
        return out
    }

    /** 批量写（同 readBatch：仅为接口兼容保留）。任一失败即抛（调用方 checkpoint 语义兜底）。 */
    fun writeBatch(dir: String, entries: List<Pair<String, ByteArray>>) {
        val base = f(dir).apply { mkdirs() }
        for ((name, bytes) in entries) {
            File(base, name).writeBytes(bytes)
        }
    }

    // ---- cache-state（同步进度）：main+bak 双文件结构保留（防写一半截断，虽然本地 FS
    //      概率远低于 SAF provider，但写盘仍可能被进程杀死，保护成本为零）----

    private val stateMain: File get() = File(File(root, STATE_DIR), STATE_MAIN)
    private val stateBak: File get() = File(File(root, STATE_DIR), STATE_BAK)

    private fun isParsable(text: String): Boolean =
        runCatching { org.json.JSONObject(text); true }.getOrDefault(false)

    fun readState(): String? {
        // 主文件必须 JSON 可解析才算完整（半写截断文本能 readText 成功但 parse 失败，R1-M3）：
        // 截断 → 回退 bak，保住 etag 记录（丢了对账全量重拉+乐观锁退化为 LWW）
        stateMain.takeIf { it.isFile }?.let { m ->
            runCatching { m.readText() }.getOrNull()?.let { if (isParsable(it)) return it }
        }
        stateBak.takeIf { it.isFile }?.let { b ->
            runCatching { b.readText() }.getOrNull()?.let { if (isParsable(it)) return it }
        }
        return null
    }

    fun writeState(json: String) {
        val dir = File(root, STATE_DIR).apply { mkdirs() }
        File(dir, STATE_BAK).writeText(json) // 先写 bak（截断旧的），再写 main——任一时刻至少一份完整
        File(dir, STATE_MAIN).writeText(json)
    }

    private companion object {
        const val STATE_DIR = ".dailynotes"
        const val STATE_MAIN = "cache-state.json"
        const val STATE_BAK = "cache-state.json.bak"
    }
}
