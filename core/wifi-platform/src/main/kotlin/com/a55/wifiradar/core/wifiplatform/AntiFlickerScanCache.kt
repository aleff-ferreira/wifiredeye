package com.a55.wifiradar.core.wifiplatform

import android.net.wifi.ScanResult
import android.os.Build
import com.a55.wifiradar.core.model.MacAddressUtils
import com.a55.wifiradar.core.model.RawScanBatch
import com.a55.wifiradar.core.model.RawScanEntry

/**
 * Rolling anti-flicker BSSID cache (`F20`):
 * - Retains missed BSSIDs for `<= 45,000 ms` with their preserved `timestampMicros < batch.receivedElapsedMs * 1_000L`.
 * - Immediately evicts stale OS entries whose hardware `ScanResult.timestamp` age exceeds `90,000 ms`
 *   (while treating synthetic `timestamp <= 0L` test `ScanResult`s as observed at `nowMicros`).
 * - Enforces a strict `512`-BSSID LRU cap and exposes latest raw [ScanResult] lookup for RTT ranging (`F13`).
 */
internal class AntiFlickerScanCache(
    private val missedRetentionWindowMs: Long = DEFAULT_MISSED_RETENTION_MS,
    private val staleEvictionThresholdMs: Long = DEFAULT_STALE_EVICTION_MS,
    private val maxCachedBssids: Int = DEFAULT_MAX_CACHED_BSSIDS,
) {
    private data class CachedBssidRecord(
        val entry: RawScanEntry,
        val rawScanResult: ScanResult,
        val lastSeenElapsedMs: Long,
        val updateSequence: Long,
    )

    private val lock = Any()
    private val cache = LinkedHashMap<String, CachedBssidRecord>()
    private var hasPriorBatch: Boolean = false
    private var lastBatchElapsedMs: Long = 0L
    private var sequenceCounter: Long = 0L

    fun ingestSweep(
        rawResults: List<ScanResult?>,
        nowElapsedMs: Long,
        nowMicros: Long = nowElapsedMs * 1_000L,
        epochMs: Long = 0L,
        isFreshHardwareSweep: Boolean = true,
        sdkInt: Int = Build.VERSION.SDK_INT,
    ): RawScanBatch = synchronized(lock) {
        val safeNowElapsedMs = nowElapsedMs.coerceAtLeast(0L)
        val effectiveBatchElapsedMs = if (hasPriorBatch && safeNowElapsedMs <= lastBatchElapsedMs) {
            lastBatchElapsedMs + 1L
        } else {
            safeNowElapsedMs
        }
        hasPriorBatch = true
        lastBatchElapsedMs = effectiveBatchElapsedMs

        val effectiveNowMicros = maxOf(nowMicros, effectiveBatchElapsedMs * 1_000L)
        val seenInCurrentSweep = HashSet<String>(rawResults.size)

        // Deduplicate same-BSSID entries within a single sweep (preferring newer timestamp, then stronger RSSI)
        val currentSweepCandidates = LinkedHashMap<String, Pair<RawScanEntry, ScanResult>>()

        for (sr in rawResults) {
            if (sr == null) continue
            val rawBssid = safeIpcCall(default = null) { sr.BSSID } ?: continue
            val normalizedBssid = MacAddressUtils.normalizeBssid(rawBssid)
            if (!MacAddressUtils.isValidMac(normalizedBssid) ||
                normalizedBssid == ZERO_MAC ||
                MacAddressUtils.isAndroidRedactedMac(normalizedBssid)
            ) {
                continue
            }

            val srTimestampMicros = safeIpcCall(default = 0L) { sr.timestamp }
            if (srTimestampMicros > 0L && effectiveNowMicros > srTimestampMicros) {
                val beaconAgeMs = (effectiveNowMicros - srTimestampMicros) / 1_000L
                if (beaconAgeMs > staleEvictionThresholdMs) {
                    cache.remove(normalizedBssid)
                    continue
                }
            }

            val extracted = ScanResultExtractor.extract(
                scanResult = sr,
                fallbackTimestampMicros = effectiveBatchElapsedMs * 1_000L,
                sdkInt = sdkInt,
            ) ?: continue

            val existingInSweep = currentSweepCandidates[extracted.bssid]
            if (existingInSweep == null || shouldReplaceWithinSameSweep(existingInSweep.first, extracted)) {
                currentSweepCandidates[extracted.bssid] = extracted to sr
            }
        }

        for ((bssid, pair) in currentSweepCandidates) {
            val (entry, sr) = pair
            val srTimestampMicros = safeIpcCall(default = 0L) { sr.timestamp }
            val seenElapsedMs = if (srTimestampMicros > 0L && effectiveNowMicros > srTimestampMicros) {
                (effectiveBatchElapsedMs - ((effectiveNowMicros - srTimestampMicros) / 1_000L))
                    .coerceAtLeast(0L)
            } else {
                effectiveBatchElapsedMs
            }
            cache[bssid] = CachedBssidRecord(
                entry = entry,
                rawScanResult = sr,
                lastSeenElapsedMs = seenElapsedMs,
                updateSequence = ++sequenceCounter,
            )
            seenInCurrentSweep.add(bssid)
        }

        // Evaluate missed entries: retain <= 45s (and <= 90s total beacon age), evict older
        val iterator = cache.entries.iterator()
        while (iterator.hasNext()) {
            val (bssid, record) = iterator.next()
            if (bssid in seenInCurrentSweep) continue

            val missedAgeMs = (effectiveBatchElapsedMs - record.lastSeenElapsedMs).coerceAtLeast(0L)
            val beaconAgeMs = if (record.entry.timestampMicros > 0L && effectiveNowMicros > record.entry.timestampMicros) {
                (effectiveNowMicros - record.entry.timestampMicros) / 1_000L
            } else {
                missedAgeMs
            }

            if (missedAgeMs > missedRetentionWindowMs || beaconAgeMs > staleEvictionThresholdMs) {
                iterator.remove()
            }
        }

        // Enforce 512-BSSID LRU cap (evict oldest lastSeenElapsedMs, then oldest updateSequence)
        if (cache.size > maxCachedBssids) {
            val excessCount = cache.size - maxCachedBssids
            val keysToEvict = cache.entries
                .sortedWith(
                    compareBy<Map.Entry<String, CachedBssidRecord>> { it.value.lastSeenElapsedMs }
                        .thenBy { it.value.updateSequence },
                )
                .take(excessCount)
                .map { it.key }
            for (key in keysToEvict) {
                cache.remove(key)
            }
        }

        val sortedEntries = cache.values
            .map { it.entry }
            .sortedWith(compareByDescending<RawScanEntry> { it.rssiDbm }.thenBy { it.bssid })

        RawScanBatch(
            entries = sortedEntries,
            receivedElapsedMs = effectiveBatchElapsedMs,
            receivedEpochMs = epochMs,
            isFreshHardwareSweep = isFreshHardwareSweep,
        )
    }

    fun getLatestRawScanResult(bssid: String): ScanResult? = synchronized(lock) {
        val normalized = MacAddressUtils.normalizeBssid(bssid)
        if (!MacAddressUtils.isValidMac(normalized)) return null
        cache[normalized]?.rawScanResult
    }

    fun size(): Int = synchronized(lock) { cache.size }

    fun clear() = synchronized(lock) {
        cache.clear()
        hasPriorBatch = false
        lastBatchElapsedMs = 0L
    }

    private fun shouldReplaceWithinSameSweep(current: RawScanEntry, candidate: RawScanEntry): Boolean =
        when {
            candidate.timestampMicros != current.timestampMicros ->
                candidate.timestampMicros > current.timestampMicros
            else -> candidate.rssiDbm >= current.rssiDbm
        }

    companion object {
        const val DEFAULT_MISSED_RETENTION_MS = 45_000L
        const val DEFAULT_STALE_EVICTION_MS = 90_000L
        const val DEFAULT_MAX_CACHED_BSSIDS = 512
        private const val ZERO_MAC = "00:00:00:00:00:00"
    }
}
