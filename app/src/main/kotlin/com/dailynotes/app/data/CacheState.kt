package com.dailynotes.app.data

import org.json.JSONObject

object CacheState {

    /** cache-state.json：path -> etag 清单 + 上次同步时间戳（毫秒，0=未知） */
    data class State(val files: Map<String, String>, val lastSync: Long)

    fun parse(json: String?): State {
        if (json.isNullOrBlank()) return State(emptyMap(), 0)
        return try {
            val obj = JSONObject(json)
            val files = obj.optJSONObject("files")
            val map = if (files != null) buildMap {
                for (key in files.keys()) put(key, files.optString(key, ""))
            } else emptyMap()
            State(map, obj.optLong("lastSync", 0))
        } catch (_: Exception) {
            State(emptyMap(), 0)   // 损坏自愈：清空状态 → 下次全量拉回
        }
    }

    fun dump(files: Map<String, String>, lastSync: Long = 0): String {
        val fo = JSONObject()
        files.forEach { (path, etag) -> fo.put(path, etag) }
        return JSONObject().put("version", 2).put("files", fo)
            .put("lastSync", lastSync)
            .toString()
    }
}
