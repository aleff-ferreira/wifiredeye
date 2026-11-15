package com.a55.wifiradar.core.wifiplatform

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope

/**
 * Deterministic [PlatformClock] backed by [TestScope.testScheduler] virtual time (plus optional
 * manual offset) so `30s`, `45s`, `90s`, and `120s` windows execute in `< 5 ms` with zero sleep.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class TestPlatformClock(
    private val testScope: TestScope? = null,
    initialElapsedMs: Long = 1_000L,
    private val baseEpochMs: Long = 1_700_000_000_000L,
) : PlatformClock {

    private var manualElapsedMs: Long = initialElapsedMs

    override fun elapsedRealtimeMs(): Long =
        manualElapsedMs + (testScope?.testScheduler?.currentTime ?: 0L)

    override fun elapsedRealtimeMicros(): Long = elapsedRealtimeMs() * 1_000L

    override fun currentTimeMillis(): Long = baseEpochMs + elapsedRealtimeMs()

    fun advanceManualMs(deltaMs: Long) {
        manualElapsedMs += deltaMs
    }

    fun setManualMs(valueMs: Long) {
        manualElapsedMs = valueMs
    }
}
