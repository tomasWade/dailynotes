package com.dailynotes.sync

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 离线快速失败行为测试：不依赖本地 sftpgo 容器——
 * 预检失败在发请求前抛；连接失败用 127.0.0.1:1（本机 RST，毫秒级 ConnectException，不用等 3s connectTimeout）。
 */
class WebDavOfflineFastFailTest {

    @Test
    fun `预检无网毫秒级快速失败（不发起连接）`() = runBlocking {
        val engine = WebDavSyncEngine("http://192.0.2.1:8090", "u", "p", networkAvailable = { false })
        val e = assertFailsWith<SyncException> { engine.list("any") }
        assertTrue(e.message!!.contains("无网络"), "应报无网络，实际：${e.message}")
    }

    @Test
    fun `连接失败触发熔断且窗内第二次零等待`() {
        WebDavSyncEngine.resetBreaker()
        val engine = WebDavSyncEngine("http://127.0.0.1:1", "u", "p", networkAvailable = { true })
        // 第一次：真实连接失败（本机 RST 毫秒级）→ 开熔断
        val e1 = assertFailsWith<SyncException> { runBlocking { engine.list("any") } }
        assertTrue(e1.message!!.contains("服务器连接不上"), "第一次应报连接失败，实际：${e1.message}")
        // 第二次：熔断挡下，零等待（若未熔断会再真实连一次）
        val t0 = System.nanoTime()
        val e2 = assertFailsWith<SyncException> { runBlocking { engine.list("any") } }
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertTrue(ms < 500, "熔断内应零等待快速失败，实际 ${ms}ms")
        assertTrue(e2.message!!.contains("自动重试"), "第二次应是熔断提示，实际：${e2.message}")
        WebDavSyncEngine.resetBreaker()
    }

    @Test
    fun `resetBreaker 后放行真实探测（用户主动重试）`() {
        val engine = WebDavSyncEngine("http://127.0.0.1:1", "u", "p", networkAvailable = { true })
        assertFailsWith<SyncException> { runBlocking { engine.list("any") } }   // 开熔断
        WebDavSyncEngine.resetBreaker()
        // 放行后仍会失败（地址本来就不可达），但报的应是真实连接错误而非熔断提示
        val e = assertFailsWith<SyncException> { runBlocking { engine.list("any") } }
        assertTrue(!e.message!!.contains("自动重试"), "reset 后应真实探测，实际被熔断挡下：${e.message}")
        WebDavSyncEngine.resetBreaker()
    }
}
