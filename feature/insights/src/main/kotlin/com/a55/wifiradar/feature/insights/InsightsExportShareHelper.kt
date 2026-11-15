package com.a55.wifiradar.feature.insights

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import com.a55.wifiradar.core.model.ExportPayload

object InsightsExportShareHelper {
    const val MAX_BINDER_SAFE_TEXT_BYTES = 60_000

    fun copyPayloadToClipboard(context: Context, payload: ExportPayload): Boolean =
        runCatching {
            val clipboard = context.getSystemService(ClipboardManager::class.java)
                ?: return@runCatching false
            val clip = ClipData.newPlainText(payload.fileName, payload.content)
            clipboard.setPrimaryClip(clip)
            true
        }.getOrDefault(false)

    fun createShareChooserIntent(
        context: Context,
        payload: ExportPayload,
    ): Intent {
        val safeText = truncateUtf8Safe(payload.content, MAX_BINDER_SAFE_TEXT_BYTES)
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = payload.mimeType
            putExtra(Intent.EXTRA_TITLE, payload.fileName)
            putExtra(Intent.EXTRA_SUBJECT, "WIFIREDEYE — ${payload.format.displayLabel}")
            putExtra(Intent.EXTRA_TEXT, safeText)
        }
        return Intent.createChooser(sendIntent, "Share ${payload.format.displayLabel}").apply {
            if (context !is Activity) {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
    }

    fun launchDefaultShareSheet(context: Context, payload: ExportPayload): Boolean =
        runCatching {
            context.startActivity(createShareChooserIntent(context, payload))
            true
        }.getOrDefault(false)

    internal fun truncateUtf8Safe(text: String, maxBytes: Int): String {
        if (maxBytes <= 0 || text.isEmpty()) return ""
        val utf8 = text.encodeToByteArray()
        if (utf8.size <= maxBytes) return text

        var endByte = maxBytes
        while (endByte > 0 && (utf8[endByte].toInt() and 0xC0) == 0x80) {
            endByte--
        }
        return utf8.decodeToString(startIndex = 0, endIndex = endByte)
    }
}
