package com.dailynotes.sync

import kotlin.test.Test
import kotlin.test.assertFailsWith

class ServerBreakerTest {

    private class Clock(var now: Long = 0L) {
        val get: () -> Long = { now }
    }

    @Test
    fun `初始放行`() {
        ServerBreaker(clock = Clock().get).check("http://a")
    }

    @Test
    fun `trip 后冷却窗内快速失败`() {
        val clock = Clock()
        val b = ServerBreaker(cooldownMs = 60_000L, clock = clock.get)
        b.trip("http://a")
        clock.now = 59_999L
        assertFailsWith<SyncException> { b.check("http://a") }
    }

    @Test
    fun `冷却到期半开放行（下次请求即探测）`() {
        val clock = Clock()
        val b = ServerBreaker(cooldownMs = 60_000L, clock = clock.get)
        b.trip("http://a")
        clock.now = 60_000L
        b.check("http://a")
    }

    @Test
    fun `reset 清空熔断（用户主动重试）`() {
        val clock = Clock()
        val b = ServerBreaker(cooldownMs = 60_000L, clock = clock.get)
        b.trip("http://a")
        assertFailsWith<SyncException> { b.check("http://a") }
        b.reset()
        b.check("http://a")
    }

    @Test
    fun `不同服务器互不影响（换服务器地址场景）`() {
        val clock = Clock()
        val b = ServerBreaker(cooldownMs = 60_000L, clock = clock.get)
        b.trip("http://a:8090")
        b.check("http://b:8090")
    }
}
