package com.dailynotes.app.data

import android.content.Context
import org.json.JSONObject

/**
 * 主页列表快照缓存（app 内部 cacheDir，java IO 毫秒级——刻意不走 SAF）。
 *
 * 目的：冷启动秒出列表。旧路径每次冷启动全量重读 diary/ 全部文件（SAF readBatch，
 * 1481 篇真机 5-10s）；快照 + mtime 对账后，无变化时零文件读取。
 *
 * 对账语义：name 的 mtime 与快照一致 → 复用快照预览；不一致/新增 → 读文件重新解析。
 * mtime=0（provider 不提供）视为稳定值（0==0 相等即跳过）——最坏情况预览陈旧到下次写。
 *
 * 安全性：快照是纯派生数据（可随时从文件重建），损坏/过期最坏 = 多读几个文件；
 * 树校验（tree 字段）防换缓存文件夹后误用旧树快照。
 */
object ListCache {

    data class CachedEntry(
        val mtime: Long,
        val preview: String,
        val firstImage: String?,
        val truncated: Boolean,
    )

    /** 读快照；无/损坏/树不匹配 → 空 Map（调用方走全量路径） */
    fun load(context: Context, dir: String, treeUri: String): Map<String, CachedEntry> {
        val f = java.io.File(context.cacheDir, "list-$dir.json")
        if (!f.exists()) return emptyMap()
        return runCatching {
            val o = JSONObject(f.readText())
            if (o.optString("tree") != treeUri) return emptyMap()
            val entries = o.getJSONObject("entries")
            val out = HashMap<String, CachedEntry>(entries.length())
            for (k in entries.keys()) {
                val e = entries.getJSONObject(k)
                out[k] = CachedEntry(
                    e.getLong("m"),
                    e.getString("p"),
                    if (e.isNull("i")) null else e.getString("i"),
                    e.getBoolean("t"),
                )
            }
            out
        }.getOrDefault(emptyMap())
    }

    /** 写快照；失败吞掉（纯派生数据，丢了下次全量重建）。
     *  temp+rename 原子替换（R10-L3）：并发 loadEntries 的交错 writeText 会撕裂 JSON——
     *  撕裂虽能自愈（parse 失败→全量重建），但白费一次 14s 重建，rename 免之。 */
    fun save(context: Context, dir: String, treeUri: String, entries: Map<String, CachedEntry>) {
        runCatching {
            val o = JSONObject()
            o.put("tree", treeUri)
            val arr = JSONObject()
            for ((k, e) in entries) {
                arr.put(k, JSONObject().put("m", e.mtime).put("p", e.preview).put("i", e.firstImage).put("t", e.truncated))
            }
            o.put("entries", arr)
            val f = java.io.File(context.cacheDir, "list-$dir.json")
            val tmp = java.io.File(context.cacheDir, "list-$dir.json.tmp")
            tmp.writeText(o.toString())
            if (!tmp.renameTo(f)) f.writeText(o.toString()) // 同目录 rename 失败极罕见，回退直写
        }
    }
}
