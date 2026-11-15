package com.a55.wifiradar.core.rfengine

/**
 * Pure-JVM numerical hygiene helpers ensuring no `NaN` or `Infinity` ever enters
 * `@Serializable` domain models or JSON/Markdown diagnostic exports.
 */
internal fun Double.finiteOr(fallback: Double = 0.0): Double =
    if (isFinite()) this else fallback

internal fun Double.finiteCoerceIn(minValue: Double, maxValue: Double, fallback: Double = minValue): Double =
    if (isFinite()) coerceIn(minValue, maxValue) else fallback
