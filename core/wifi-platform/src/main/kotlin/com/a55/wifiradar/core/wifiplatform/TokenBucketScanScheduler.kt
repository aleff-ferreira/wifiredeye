package com.a55.wifiradar.core.wifiplatform

import com.a55.wifiradar.core.model.ScanTelemetry
import com.a55.wifiradar.core.model.ScanTriggerResult

/**
 * Deterministic rolling `4 scans / 120,000 ms` token-bucket & active scan scheduler (`F20`).
 *
 * - Throttled steady mode (`isScanThrottleEnabled == true`, `!burstMode`): `30,000 ms` spacing.
 * - Burst Homing mode (`burstMode == true`): `4,000 ms` rapid pulses while `availableTokens > 0`.
 * - Unthrottled Developer mode (`isScanThrottleEnabled == false`): `2,500 ms` continuous sweeps
 *   with `400 ms` post-callback debounce.
 * - Promotes OS scan rejection (`startScan() == false` or `EXTRA_RESULTS_UPDATED == false`) into
 *   `ScanTelemetry` (`isScanThrottleEnabled = true`, `isCurrentlyThrottled = true`, non-null
 *   `lastScanTimestampMs`) so `SemanticFormatters.formatScanStatusPill` renders
 *   `"OS Throttle Active · Next active scan in Xs (Passive Live)"`.
 */
internal class TokenBucketScanScheduler(
    private val maxTokens: Int = MAX_TOKENS,
    private val tokenWindowMs: Long = TOKEN_WINDOW_MS,
) {
    sealed interface PreTriggerCheck {
        data object Proceed : PreTriggerCheck
        data class BlockedByTokenBucket(val nextTokenInMs: Long) : PreTriggerCheck
    }

    private val lock = Any()
    private val activeScanTimestampsMs = ArrayDeque<Long>()

    private var totalActiveScansTriggered: Int = 0
    private var totalPassiveScansReceived: Int = 0
    private var pendingActiveScanAck: Boolean = false
    private var isBurstModeActiveFlag: Boolean = false
    private var lastTriggerRejectedByOsFlag: Boolean = false
    private var isPassiveOnlyRestrictedProfileFlag: Boolean = false
    private var consecutiveColdRejections: Int = 0
    private var osThrottleUntilElapsedMs: Long = 0L
    private var lastScanTimestampMs: Long? = null
    private var lastCallbackElapsedMs: Long = -POST_CALLBACK_DEBOUNCE_MS
    private var nextScheduledScanAtElapsedMs: Long = 0L

    val isBurstModeActive: Boolean
        get() = synchronized(lock) { isBurstModeActiveFlag }

    fun checkPreTrigger(nowElapsedMs: Long, rawThrottleEnabled: Boolean): PreTriggerCheck =
        synchronized(lock) {
            val now = nowElapsedMs.coerceAtLeast(0L)
            pruneExpiredLocked(now)
            if (!rawThrottleEnabled) {
                return PreTriggerCheck.Proceed
            }
            val available = (maxTokens - activeScanTimestampsMs.size).coerceIn(0, maxTokens)
            if (available == 0) {
                isBurstModeActiveFlag = false
                val nextTokenInMs = computeWindowRechargeMsLocked(now, rawThrottleEnabled = true)
                    .coerceAtLeast(1L)
                nextScheduledScanAtElapsedMs = now + nextTokenInMs
                lastScanTimestampMs = lastScanTimestampMs ?: now
                return PreTriggerCheck.BlockedByTokenBucket(nextTokenInMs = nextTokenInMs)
            }
            PreTriggerCheck.Proceed
        }

    fun onStartScanAccepted(
        nowElapsedMs: Long,
        rawThrottleEnabled: Boolean,
        burstMode: Boolean,
    ): ScanTriggerResult.Triggered = synchronized(lock) {
        val now = nowElapsedMs.coerceAtLeast(0L)
        pruneExpiredLocked(now)

        consecutiveColdRejections = 0
        isPassiveOnlyRestrictedProfileFlag = false
        lastTriggerRejectedByOsFlag = false
        osThrottleUntilElapsedMs = 0L
        totalActiveScansTriggered++
        pendingActiveScanAck = true

        return if (rawThrottleEnabled) {
            activeScanTimestampsMs.addLast(now)
            val remaining = (maxTokens - activeScanTimestampsMs.size).coerceIn(0, maxTokens)
            isBurstModeActiveFlag = if (burstMode) {
                remaining > 0
            } else {
                isBurstModeActiveFlag && remaining > 0
            }
            nextScheduledScanAtElapsedMs = when {
                remaining == 0 -> {
                    val rechargeMs = computeWindowRechargeMsLocked(now, rawThrottleEnabled = true)
                        .coerceAtLeast(1L)
                    now + rechargeMs
                }
                isBurstModeActiveFlag -> now + BURST_HOMING_INTERVAL_MS
                else -> now + STEADY_THROTTLED_INTERVAL_MS
            }
            ScanTriggerResult.Triggered(
                remainingTokens = remaining,
                unthrottledMode = false,
            )
        } else {
            isBurstModeActiveFlag = false
            nextScheduledScanAtElapsedMs = now + UNTHROTTLED_INTERVAL_MS
            ScanTriggerResult.Triggered(
                remainingTokens = maxTokens,
                unthrottledMode = true,
            )
        }
    }

    fun onStartScanRejectedByOs(
        nowElapsedMs: Long,
        rawThrottleEnabled: Boolean,
    ): ScanTriggerResult.RejectedByOs = synchronized(lock) {
        val now = nowElapsedMs.coerceAtLeast(0L)
        pruneExpiredLocked(now)

        consecutiveColdRejections++
        if (totalActiveScansTriggered == 0 && consecutiveColdRejections >= COLD_REJECTION_PROFILE_THRESHOLD) {
            isPassiveOnlyRestrictedProfileFlag = true
        }
        lastTriggerRejectedByOsFlag = true
        isBurstModeActiveFlag = false
        pendingActiveScanAck = false
        lastScanTimestampMs = lastScanTimestampMs ?: now

        val windowRechargeMs = computeWindowRechargeMsLocked(now, rawThrottleEnabled)
        val retryInMs = maxOf(windowRechargeMs, OS_REJECT_RETRY_MS)
        osThrottleUntilElapsedMs = now + retryInMs
        nextScheduledScanAtElapsedMs = now + retryInMs

        ScanTriggerResult.RejectedByOs(nextRetryInMs = retryInMs)
    }

    fun onBroadcastRejectedByOs(nowElapsedMs: Long, rawThrottleEnabled: Boolean) = synchronized(lock) {
        val now = nowElapsedMs.coerceAtLeast(0L)
        pruneExpiredLocked(now)

        lastTriggerRejectedByOsFlag = true
        isBurstModeActiveFlag = false
        pendingActiveScanAck = false
        lastScanTimestampMs = lastScanTimestampMs ?: now

        val windowRechargeMs = computeWindowRechargeMsLocked(now, rawThrottleEnabled)
        val retryInMs = maxOf(windowRechargeMs, OS_REJECT_RETRY_MS)
        osThrottleUntilElapsedMs = maxOf(osThrottleUntilElapsedMs, now + retryInMs)
        nextScheduledScanAtElapsedMs = maxOf(nextScheduledScanAtElapsedMs, now + retryInMs)
    }

    fun onSeedCacheHarvested(nowElapsedMs: Long) = synchronized(lock) {
        val now = nowElapsedMs.coerceAtLeast(0L)
        if (lastScanTimestampMs == null) {
            lastScanTimestampMs = now
        }
    }

    fun onFreshSweepIngested(nowElapsedMs: Long, rawThrottleEnabled: Boolean) = synchronized(lock) {
        val now = nowElapsedMs.coerceAtLeast(0L)
        pruneExpiredLocked(now)

        lastCallbackElapsedMs = now
        lastScanTimestampMs = now
        lastTriggerRejectedByOsFlag = false
        osThrottleUntilElapsedMs = 0L

        if (pendingActiveScanAck) {
            pendingActiveScanAck = false
        } else {
            totalPassiveScansReceived++
        }

        nextScheduledScanAtElapsedMs = if (!rawThrottleEnabled) {
            maxOf(
                nextScheduledScanAtElapsedMs,
                now + UNTHROTTLED_INTERVAL_MS,
                now + POST_CALLBACK_DEBOUNCE_MS,
            )
        } else {
            maxOf(nextScheduledScanAtElapsedMs, now + POST_CALLBACK_DEBOUNCE_MS)
        }
    }

    fun shouldAutoTriggerAt(nowElapsedMs: Long, rawThrottleEnabled: Boolean): Boolean =
        synchronized(lock) {
            val now = nowElapsedMs.coerceAtLeast(0L)
            pruneExpiredLocked(now)
            if (osThrottleUntilElapsedMs > 0L && now < osThrottleUntilElapsedMs) {
                return false
            }
            if (rawThrottleEnabled && activeScanTimestampsMs.size >= maxTokens) {
                return false
            }
            now >= nextScheduledScanAtElapsedMs &&
                (now - lastCallbackElapsedMs) >= POST_CALLBACK_DEBOUNCE_MS
        }

    fun computeNextWakeDelayMs(nowElapsedMs: Long, maxTickMs: Long = TELEMETRY_TICK_MS): Long =
        synchronized(lock) {
            val now = nowElapsedMs.coerceAtLeast(0L)
            pruneExpiredLocked(now)
            val earliestReadyAt = maxOf(
                nextScheduledScanAtElapsedMs,
                lastCallbackElapsedMs + POST_CALLBACK_DEBOUNCE_MS,
            )
            if (earliestReadyAt <= now) {
                MIN_WAKE_DELAY_MS
            } else {
                minOf(maxTickMs, (earliestReadyAt - now).coerceAtLeast(MIN_WAKE_DELAY_MS))
            }
        }

    fun snapshotTelemetry(nowElapsedMs: Long, rawThrottleEnabled: Boolean): ScanTelemetry =
        synchronized(lock) {
            val now = nowElapsedMs.coerceAtLeast(0L)
            pruneExpiredLocked(now)

            val availableTokens = if (rawThrottleEnabled) {
                (maxTokens - activeScanTimestampsMs.size).coerceIn(0, maxTokens)
            } else {
                maxTokens
            }
            if (availableTokens == 0) {
                isBurstModeActiveFlag = false
            }

            val windowRechargeMs = computeWindowRechargeMsLocked(now, rawThrottleEnabled)
            val osCooldownRemainingMs = (osThrottleUntilElapsedMs - now).coerceAtLeast(0L)
            val isCurrentlyThrottled =
                (rawThrottleEnabled && availableTokens == 0) || osCooldownRemainingMs > 0L
            val effectiveThrottleEnabled =
                rawThrottleEnabled || lastTriggerRejectedByOsFlag || isPassiveOnlyRestrictedProfileFlag
            val nextRechargeMs = maxOf(windowRechargeMs, osCooldownRemainingMs)
            val lastScanAgeMs = lastScanTimestampMs?.let { (now - it).coerceAtLeast(0L) } ?: 0L
            val nextScheduledInMs =
                if (nextScheduledScanAtElapsedMs > now) nextScheduledScanAtElapsedMs - now else 0L

            ScanTelemetry(
                lastScanTimestampMs = lastScanTimestampMs,
                lastScanAgeMs = lastScanAgeMs,
                totalActiveScansTriggered = totalActiveScansTriggered,
                totalPassiveScansReceived = totalPassiveScansReceived,
                isScanThrottleEnabled = effectiveThrottleEnabled,
                isCurrentlyThrottled = isCurrentlyThrottled,
                availableTokens = availableTokens,
                maxTokens = maxTokens,
                tokenWindowMs = tokenWindowMs,
                nextTokenRechargeInMs = nextRechargeMs,
                nextScheduledScanInMs = nextScheduledInMs,
                isBurstModeActive = isBurstModeActiveFlag,
                lastTriggerRejectedByOs = lastTriggerRejectedByOsFlag,
                isPassiveOnlyRestrictedProfile = isPassiveOnlyRestrictedProfileFlag,
            )
        }

    private fun pruneExpiredLocked(nowElapsedMs: Long) {
        while (activeScanTimestampsMs.isNotEmpty() &&
            nowElapsedMs - activeScanTimestampsMs.first() >= tokenWindowMs
        ) {
            activeScanTimestampsMs.removeFirst()
        }
        if (osThrottleUntilElapsedMs > 0L && nowElapsedMs >= osThrottleUntilElapsedMs) {
            osThrottleUntilElapsedMs = 0L
            lastTriggerRejectedByOsFlag = false
        }
    }

    private fun computeWindowRechargeMsLocked(nowElapsedMs: Long, rawThrottleEnabled: Boolean): Long {
        if (!rawThrottleEnabled || activeScanTimestampsMs.isEmpty()) return 0L
        if (activeScanTimestampsMs.size >= maxTokens || activeScanTimestampsMs.isNotEmpty()) {
            val oldest = activeScanTimestampsMs.first()
            return (tokenWindowMs - (nowElapsedMs - oldest)).coerceAtLeast(0L)
        }
        return 0L
    }

    companion object {
        const val MAX_TOKENS = 4
        const val TOKEN_WINDOW_MS = 120_000L
        const val STEADY_THROTTLED_INTERVAL_MS = 30_000L
        const val BURST_HOMING_INTERVAL_MS = 4_000L
        const val UNTHROTTLED_INTERVAL_MS = 2_500L
        const val POST_CALLBACK_DEBOUNCE_MS = 400L
        const val OS_REJECT_RETRY_MS = 30_000L
        const val TELEMETRY_TICK_MS = 1_000L
        private const val MIN_WAKE_DELAY_MS = 25L
        private const val COLD_REJECTION_PROFILE_THRESHOLD = 3
    }
}
