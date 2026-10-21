package com.dailynotes.sync

typealias Etag = String

interface SyncEngine {
    suspend fun list(dir: String): Map<String, Etag>
    suspend fun listDirs(dir: String): List<String>
    suspend fun get(path: String): Pair<ByteArray, Etag>
    suspend fun put(path: String, data: ByteArray): Etag
    suspend fun delete(path: String)
    suspend fun mkdir(path: String)
    /** 单文件当前远端 etag；文件不存在或服务器不支持返回 "" */
    suspend fun etagOf(path: String): Etag

    companion object {
        val TOP_DIRS = listOf("diary", "notes", "attachments")
    }
}

class SyncException(message: String, val statusCode: Int? = null, cause: Throwable? = null) :
    Exception(message, cause)
