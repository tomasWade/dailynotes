package com.dailynotes.sync

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.w3c.dom.Element
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import javax.xml.parsers.DocumentBuilderFactory

class WebDavSyncEngine(
    baseUrl: String,
    username: String,
    password: String,
) : SyncEngine {

    private val base = baseUrl.trimEnd('/')
    private val authHeader = Credentials.basic(username, password)

    companion object {
        // 共享连接池/线程池：CacheSyncer 每次同步都会 new engine，独立 client 会积累资源
        private val client: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    private fun request(
        method: String,
        path: String,
        body: RequestBody? = null,
        headers: Map<String, String> = emptyMap(),
    ): okhttp3.Response = client.newCall(
        Request.Builder()
            .url(url(path))
            .method(method, body)
            .header("Authorization", authHeader)
            .apply { headers.forEach { (k, v) -> header(k, v) } }
            .build()
    ).execute()

    private fun url(path: String): String {
        val encoded = path.split("/").joinToString("/") {
            java.net.URLEncoder.encode(it, "UTF-8").replace("+", "%20")
        }
        return "$base/$encoded"
    }

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }

    private inline fun ok(code: Int, vararg acceptable: Int): Boolean = code in acceptable

    override suspend fun mkdir(path: String) = io {
        var current = ""
        for (segment in path.trim('/').split("/").filter { it.isNotBlank() }) {
            current = "$current/$segment"
            request("MKCOL", "$current/").use { resp ->
                if (!ok(resp.code, 201, 405)) throw SyncException("MKCOL $current -> ${resp.code}", resp.code)
            }
        }
        Unit
    }

    override suspend fun list(dir: String): Map<String, Etag> {
        val (files, _) = propfind(dir)
        return files
    }

    override suspend fun listDirs(dir: String): List<String> {
        val (_, dirs) = propfind(dir)
        return dirs
    }

    private suspend fun propfind(dir: String): Pair<Map<String, Etag>, List<String>> = io {
        val resp = request(
            "PROPFIND", dir.trimEnd('/') + "/",
            headers = mapOf("Depth" to "1", "Content-Type" to "application/xml"),
        )
        resp.use {
            if (it.code != 207) throw SyncException("PROPFIND $dir -> ${it.code}", it.code)
            val xml = it.body?.bytes() ?: throw SyncException("PROPFIND $dir empty body")
            parseMultistatus(xml, dir.trimEnd('/'))
        }
    }

    override suspend fun get(path: String): Pair<ByteArray, Etag> = io {
        request("GET", path).use { resp ->
            if (resp.code != 200) throw SyncException("GET $path -> ${resp.code}", resp.code)
            val bytes = resp.body?.bytes() ?: ByteArray(0)
            bytes to (resp.header("ETag") ?: "")
        }
    }

    override suspend fun put(path: String, data: ByteArray): Etag {
        // WebDAV 要求父目录已存在（SFTPGo 否则 409）——附件月桶等深层路径首次上传时必然踩中。
        // 无 '/' 时返回 ""（避免把整个 path 当目录 MKCOL 出同名目录导致后续 PUT 409）
        mkdir(path.substringBeforeLast('/', ""))
        val headerEtag = io {
            request("PUT", path, data.toRequestBody(null)).use { resp ->
                if (!ok(resp.code, 200, 201, 204)) throw SyncException("PUT $path -> ${resp.code}", resp.code)
                resp.header("ETag")
            }
        }
        return headerEtag ?: fetchEtag(path)
    }

    override suspend fun delete(path: String) = io {
        request("DELETE", path).use { resp ->
            if (!ok(resp.code, 200, 202, 204, 404)) throw SyncException("DELETE $path -> ${resp.code}", resp.code)
        }
    }

    private suspend fun fetchEtag(path: String): Etag = io {
        val resp = request("PROPFIND", path, headers = mapOf("Depth" to "0"))
        resp.use {
            if (it.code != 207) return@use ""
            val xml = it.body?.bytes() ?: return@use ""
            parseMultistatus(xml, path.substringBeforeLast('/')).first.values.firstOrNull() ?: ""
        }
    }

    private fun parseMultistatus(xml: ByteArray, dirPath: String): Pair<Map<String, Etag>, List<String>> {
        val doc = DocumentBuilderFactory.newInstance()
            .apply { isNamespaceAware = true }
            .newDocumentBuilder()
            .parse(xml.inputStream())
        val files = mutableMapOf<String, Etag>()
        val dirs = mutableListOf<String>()
        val responses = doc.getElementsByTagNameNS("*", "response")
        val dirName = dirPath.trim('/')
        for (i in 0 until responses.length) {
            val el = responses.item(i) as Element
            val href = el.getElementsByTagNameNS("*", "href").item(0)?.textContent ?: continue
            val trimmed = href.trim('/')
            if (trimmed.isEmpty() || trimmed == dirName) continue
            val name = trimmed.substringAfterLast('/')
            if (name.isEmpty()) continue
            if (el.getElementsByTagNameNS("*", "collection").length > 0) {
                dirs += "$dirPath/$name"
            } else {
                val etag = el.getElementsByTagNameNS("*", "getetag").item(0)?.textContent ?: continue
                files["$dirPath/$name"] = etag
            }
        }
        return files to dirs
    }
}
