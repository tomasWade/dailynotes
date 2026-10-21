package com.dailynotes.sync

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SyncCoordinatorTest {

    private val engine = WebDavSyncEngine(
        baseUrl = System.getenv("DAV_BASE") ?: "http://127.0.0.1:18090",
        username = System.getenv("DAV_USER") ?: "probe",
        password = System.getenv("DAV_PASS") ?: "probepass123",
    )
    private fun coordinator(vararg dirs: String) = SyncCoordinator(engine, dirs.toList())

    @Test
    fun `首拉取回全部文件`() = runBlocking {
        engine.mkdir("t-diary")
        engine.put("t-diary/a.md", "a 内容".toByteArray())
        engine.put("t-diary/b.md", "b 内容".toByteArray())

        val result = coordinator("t-diary").pull(known = emptyMap())
        val paths = result.updated.map { it.path }
        assertTrue("t-diary/a.md" in paths && "t-diary/b.md" in paths, "应拉回两个文件: $paths")
        assertEquals(0, result.deleted.size)
        engine.delete("t-diary/a.md")
        engine.delete("t-diary/b.md")
    }

    @Test
    fun `etag 未变的文件跳过`() = runBlocking {
        engine.mkdir("t-diary")
        val etag = engine.put("t-diary/c.md", "c 内容".toByteArray())

        val first = coordinator("t-diary").pull(emptyMap())
        val cFile = first.updated.first { it.path == "t-diary/c.md" }

        val second = coordinator("t-diary").pull(mapOf("t-diary/c.md" to cFile.etag.ifBlank { etag }))
        assertEquals(0, second.updated.count { it.path == "t-diary/c.md" }, "etag 相同不应重拉")
        assertTrue(second.unchangedCount >= 1)
        engine.delete("t-diary/c.md")
    }

    @Test
    fun `服务器删除被检测到`() = runBlocking {
        engine.mkdir("t-diary")
        engine.put("t-diary/d.md", "d".toByteArray())
        val first = coordinator("t-diary").pull(emptyMap())
        val dEtag = first.updated.first { it.path == "t-diary/d.md" }.etag

        engine.delete("t-diary/d.md")
        val second = coordinator("t-diary").pull(mapOf("t-diary/d.md" to dEtag))
        assertTrue("t-diary/d.md" in second.deleted, "应检测到远端删除")
    }

    @Test
    fun `attachments 子目录递归`() = runBlocking {
        engine.mkdir("t-attachments/2026-10")
        engine.put("t-attachments/2026-10/img.jpg", "fake".toByteArray())

        val result = coordinator("t-attachments").pull(emptyMap())
        assertTrue(
            result.updated.any { it.path == "t-attachments/2026-10/img.jpg" },
            "应递归拉到子目录文件: ${result.updated.map { it.path }}",
        )
        engine.delete("t-attachments/2026-10/img.jpg")
    }

    @Test
    fun `push 返回 etag 且 pull 对账跳过`() = runBlocking {
        engine.mkdir("t-diary")
        val etag = coordinator("t-diary").push("t-diary/e.md", "e v1".toByteArray())
        assertTrue(etag.isNotBlank(), "push 应返回 etag")

        val after = coordinator("t-diary").pull(mapOf("t-diary/e.md" to etag))
        assertEquals(0, after.updated.count { it.path == "t-diary/e.md" }, "push 后对账应跳过")

        val etag2 = coordinator("t-diary").push("t-diary/e.md", "e v2".toByteArray())
        assertTrue(etag2.isNotBlank() && etag2 != etag, "内容变化后 etag 应变化")
        engine.delete("t-diary/e.md")
    }

    @Test
    fun `delete 远端后 pull 报告删除且不重拉`() = runBlocking {
        engine.mkdir("t-diary")
        val etag = coordinator("t-diary").push("t-diary/f.md", "f".toByteArray())

        coordinator("t-diary").delete("t-diary/f.md")
        // 已知 etag 但远端没了 → deleted；known 里也没有 → 不会当新文件拉回
        val after = coordinator("t-diary").pull(mapOf("t-diary/f.md" to etag))
        assertTrue("t-diary/f.md" in after.deleted)
        val fresh = coordinator("t-diary").pull(emptyMap())
        assertTrue("t-diary/f.md" !in fresh.updated.map { it.path }, "远端已删不应被拉回")
    }
}
