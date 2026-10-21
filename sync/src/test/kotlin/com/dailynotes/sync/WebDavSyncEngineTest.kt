package com.dailynotes.sync

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class WebDavSyncEngineTest {

    private val engine = WebDavSyncEngine(
        baseUrl = System.getenv("DAV_BASE") ?: "http://127.0.0.1:18090",
        username = System.getenv("DAV_USER") ?: "probe",
        password = System.getenv("DAV_PASS") ?: "probepass123",
    )

    @Test
    fun `mkdir 幂等`() = runBlocking {
        engine.mkdir("t-diary")
        engine.mkdir("t-diary")
    }

    @Test
    fun `put 自动创建多级父目录（附件月桶场景）`() = runBlocking {
        // 不预先 mkdir，直接 PUT 深层路径——真机 409 的回归用例
        val etag = engine.put("t-attach-bucket/2026-10/img_001.jpg", "fake-image-bytes".toByteArray())
        assertTrue(etag.isNotBlank(), "etag 应非空")

        val (bytes, _) = engine.get("t-attach-bucket/2026-10/img_001.jpg")
        assertEquals("fake-image-bytes", bytes.toString(Charsets.UTF_8))
        engine.delete("t-attach-bucket/2026-10/img_001.jpg")
    }

    @Test
    fun `put 顶层文件无目录前缀不建同名目录`() = runBlocking {
        // substringBeforeLast 防御：无 '/' 时不得把文件名当目录 MKCOL（否则 PUT 409）
        val etag = engine.put("t-toplevel-file.md", "top".toByteArray())
        assertTrue(etag.isNotBlank())
        val (bytes, _) = engine.get("t-toplevel-file.md")
        assertEquals("top", bytes.toString(Charsets.UTF_8))
        engine.delete("t-toplevel-file.md")
    }

    @Test
    fun `put get list roundtrip`() = runBlocking {
        engine.mkdir("t-diary")
        val body = "# 测试日记内容 🎉".toByteArray()
        val etag1 = engine.put("t-diary/2026-10-21.md", body)
        assertTrue(etag1.isNotBlank(), "etag 应非空")

        val (downloaded, etagGet) = engine.get("t-diary/2026-10-21.md")
        assertTrue(downloaded.contentEquals(body), "内容应一致")
        assertEquals(etag1, etagGet, "GET 与 PUT 的 etag 应一致")

        val list = engine.list("t-diary")
        assertTrue(list.containsKey("t-diary/2026-10-21.md"), "列表应含该文件")
        assertEquals(etag1, list["t-diary/2026-10-21.md"])

        val etag2 = engine.put("t-diary/2026-10-21.md", "# 改过的".toByteArray())
        assertTrue(etag2 != etag1, "覆盖后 etag 应变化")
        engine.delete("t-diary/2026-10-21.md")
    }

    @Test
    fun `list 空目录返回空 map`() = runBlocking {
        engine.mkdir("t-empty")
        val list = engine.list("t-empty")
        assertTrue(list.isEmpty())
    }

    @Test
    fun `get 不存在的文件抛异常`() {
        assertFailsWith<SyncException> {
            runBlocking { engine.get("t-diary/no-such-file.md") }
        }
    }

    @Test
    fun `delete 不存在的文件幂等成功`() = runBlocking {
        engine.delete("t-diary/never-exists.md")
    }
}
