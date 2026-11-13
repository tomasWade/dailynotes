package com.dailynotes.sync

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * 进程级服务器熔断：同一服务器连接失败后，冷却窗口内后续请求零等待快速失败。
 *
 * 动机：离线的典型形态是「手机有网但服务器不可达」（连着 Wi-Fi 不在内网 / 服务器挂了）——
 * ConnectivityManager 判不出来，只能等 connectTimeout；且每次打开 App / 保存 / 翻页恢复
 * 都重新等一遍。熔断把第 2..N 次的等待变成 0，冷却到期自动半开放行探测。
 *
 * 只由「请求未得到响应」类失败触发：连接失败（拒连/DNS）或响应头等待超时（execute() 阶段）——
 * 二者都说明「别再敲它了」；已拿到响应（哪怕 5xx）说明服务器活着，不触发。
 * 按服务器地址隔离：设置里换服务器后不受旧熔断误伤。
 *
 * 时钟用单调的 nanoTime：墙钟（currentTimeMillis）回拨（手动改时间/NTP 校正）会拉长熔断窗。
 */
class ServerBreaker(
    private val cooldownMs: Long = DEFAULT_COOLDOWN_MS,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },   // 单调毫秒（nanoTime 换算），单位与 cooldownMs 一致
) {
    private val openUntil = ConcurrentHashMap<String, AtomicLong>()

    /** 熔断开启时抛 [SyncException]（message 直接面向用户），否则静默放行；到期视为半开放行 */
    fun check(server: String) {
        val until = openUntil[server]?.get() ?: return
        val remaining = until - clock()
        if (remaining > 0) {
            throw SyncException("服务器连接不上（约 ${(remaining + 999) / 1000} 秒后自动重试）")
        }
    }

    fun trip(server: String) {
        openUntil.getOrPut(server) { AtomicLong() }.set(clock() + cooldownMs)
    }

    /** 用户主动重试入口（手动同步/保存）调用：清空熔断，本次请求真实探测 */
    fun reset() = openUntil.clear()

    companion object {
        const val DEFAULT_COOLDOWN_MS = 60_000L
    }
}
