package com.a55.wifiradar.core.rfengine

import com.a55.wifiradar.core.model.RssiSample
import com.a55.wifiradar.core.model.SparklinePoint
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Zero-growth primitive-array circular ring buffer (`capacity <= 120`) storing historical
 * RSSI samples and dropout gap markers in `O(1)` flat memory (`F14`, `F29`).
 */
internal class FixedRssiRingBuffer(
    val capacity: Int = MAX_SAMPLES_PER_BSSID,
) {
    companion object {
        const val MAX_SAMPLES_PER_BSSID: Int = 120
        private const val DROPOUT_RAW_SENTINEL: Int = Int.MIN_VALUE
        private const val FLAG_CONNECTED: Int = 0x01
        private const val FLAG_DROPOUT_GAP: Int = 0x02
    }

    init {
        require(capacity in 1..MAX_SAMPLES_PER_BSSID) {
            "Ring buffer capacity must be in 1..$MAX_SAMPLES_PER_BSSID, got $capacity"
        }
    }

    private val timestampsMs = LongArray(capacity)
    private val rawDbm = IntArray(capacity)
    private val smoothedDbm = DoubleArray(capacity)
    private val emaDbm = DoubleArray(capacity)
    private val flags = ByteArray(capacity)

    private var head: Int = 0
    var size: Int = 0
        private set

    fun clear() {
        head = 0
        size = 0
    }

    fun appendLiveSample(
        timestampElapsedMs: Long,
        rawRssiDbm: Int,
        smoothedRssiDbm: Double,
        emaRssiDbm: Double,
        isConnectedSample: Boolean,
    ) {
        if (size > 0) {
            val lastIdx = physicalIndex(size - 1)
            val isLastGap = (flags[lastIdx].toInt() and FLAG_DROPOUT_GAP) != 0
            if (!isLastGap &&
                timestampsMs[lastIdx] == timestampElapsedMs &&
                rawDbm[lastIdx] == rawRssiDbm
            ) {
                this.smoothedDbm[lastIdx] = smoothedRssiDbm.finiteOr(rawRssiDbm.toDouble())
                this.emaDbm[lastIdx] = emaRssiDbm.finiteOr(rawRssiDbm.toDouble())
                if (isConnectedSample) {
                    flags[lastIdx] = (flags[lastIdx].toInt() or FLAG_CONNECTED).toByte()
                }
                return
            }
        }
        writeSlot(
            timestampElapsedMs = timestampElapsedMs,
            rawRssiDbm = rawRssiDbm,
            smoothedRssi = smoothedRssiDbm.finiteOr(rawRssiDbm.toDouble()),
            emaRssi = emaRssiDbm.finiteOr(rawRssiDbm.toDouble()),
            flagBits = if (isConnectedSample) FLAG_CONNECTED else 0,
        )
    }

    /**
     * Inserts a single dropout gap marker, or updates the trailing gap marker's timestamp
     * in place so prolonged target dropouts never evict pre-dropout signal history (`F29`).
     */
    fun appendOrUpdateDropoutGap(timestampElapsedMs: Long) {
        if (size > 0) {
            val lastIdx = physicalIndex(size - 1)
            if ((flags[lastIdx].toInt() and FLAG_DROPOUT_GAP) != 0) {
                timestampsMs[lastIdx] = timestampElapsedMs
                return
            }
        }
        writeSlot(
            timestampElapsedMs = timestampElapsedMs,
            rawRssiDbm = DROPOUT_RAW_SENTINEL,
            smoothedRssi = Double.NaN,
            emaRssi = Double.NaN,
            flagBits = FLAG_DROPOUT_GAP,
        )
    }

    fun toRssiSampleList(): List<RssiSample> {
        if (size == 0) return emptyList()
        val out = ArrayList<RssiSample>(size)
        for (i in 0 until size) {
            val idx = physicalIndex(i)
            val flagInt = flags[idx].toInt()
            val isGap = (flagInt and FLAG_DROPOUT_GAP) != 0
            out.add(
                RssiSample(
                    timestampElapsedMs = timestampsMs[idx],
                    rssiDbm = if (isGap) null else rawDbm[idx],
                    smoothedRssiDbm = if (isGap) null else smoothedDbm[idx].finiteOr(0.0),
                    isConnectedSample = !isGap && (flagInt and FLAG_CONNECTED) != 0,
                )
            )
        }
        return out
    }

    fun toSparklinePoints(): List<SparklinePoint> {
        if (size == 0) return emptyList()
        val out = ArrayList<SparklinePoint>(size)
        for (i in 0 until size) {
            val idx = physicalIndex(i)
            val flagInt = flags[idx].toInt()
            val isGap = (flagInt and FLAG_DROPOUT_GAP) != 0
            out.add(
                SparklinePoint(
                    timestampElapsedMs = timestampsMs[idx],
                    rawRssiDbm = if (isGap) null else rawDbm[idx],
                    smoothedRssiDbm = if (isGap) null else smoothedDbm[idx].finiteOr(0.0),
                    emaRssiDbm = if (isGap) null else emaDbm[idx].finiteOr(0.0),
                    isDropoutGap = isGap,
                )
            )
        }
        return out
    }

    /**
     * Computes `(stabilityPct, stdDevDb)` over the last `windowSize` live (non-dropout) raw RSSI samples.
     */
    fun computeRollingRawStability(windowSize: Int = 20): Pair<Int, Double> {
        var count = 0
        var sum = 0.0
        for (i in (size - 1) downTo 0) {
            val idx = physicalIndex(i)
            if ((flags[idx].toInt() and FLAG_DROPOUT_GAP) == 0) {
                sum += rawDbm[idx].toDouble()
                count++
                if (count >= windowSize) break
            }
        }
        if (count <= 1) return 100 to 0.0

        val mean = sum / count.toDouble()
        var sqDiffSum = 0.0
        var seen = 0
        for (i in (size - 1) downTo 0) {
            val idx = physicalIndex(i)
            if ((flags[idx].toInt() and FLAG_DROPOUT_GAP) == 0) {
                val diff = rawDbm[idx].toDouble() - mean
                sqDiffSum += diff * diff
                seen++
                if (seen >= count) break
            }
        }

        val stdDev = sqrt((sqDiffSum / count.toDouble()).coerceAtLeast(0.0)).finiteOr(0.0)
        val stabilityPct = (100.0 * (1.0 - max(0.0, stdDev - 0.8) / 9.2))
            .finiteOr(100.0)
            .roundToInt()
            .coerceIn(0, 100)
        return stabilityPct to stdDev
    }

    private fun writeSlot(
        timestampElapsedMs: Long,
        rawRssiDbm: Int,
        smoothedRssi: Double,
        emaRssi: Double,
        flagBits: Int,
    ) {
        val writeIdx = if (size < capacity) {
            val idx = (head + size) % capacity
            size++
            idx
        } else {
            val idx = head
            head = (head + 1) % capacity
            idx
        }
        timestampsMs[writeIdx] = timestampElapsedMs
        rawDbm[writeIdx] = rawRssiDbm
        smoothedDbm[writeIdx] = smoothedRssi
        emaDbm[writeIdx] = emaRssi
        flags[writeIdx] = flagBits.toByte()
    }

    private fun physicalIndex(logicalIndex: Int): Int =
        (head + logicalIndex) % capacity
}
