package com.a55.wifiradar.core.wifiplatform

import android.os.SystemClock

/**
 * Monotonic and wall-clock time source shared across `:core:wifi-platform` so unit tests can
 * advance virtual time deterministically in milliseconds without `Thread.sleep`.
 */
interface PlatformClock {
    fun elapsedRealtimeMs(): Long

    fun elapsedRealtimeMicros(): Long = elapsedRealtimeMs() * 1_000L

    fun currentTimeMillis(): Long = System.currentTimeMillis()
}

object SystemPlatformClock : PlatformClock {
    override fun elapsedRealtimeMs(): Long = SystemClock.elapsedRealtime()

    override fun elapsedRealtimeMicros(): Long = SystemClock.elapsedRealtimeNanos() / 1_000L

    override fun currentTimeMillis(): Long = System.currentTimeMillis()
}
