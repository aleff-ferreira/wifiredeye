package com.a55.wifiradar.e2e.robots

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.robolectric.Shadows

/**
 * Inspects Android OS Share Sheet (`ACTION_CHOOSER` / `ACTION_SEND`), `FileProvider` `content://`
 * stream URIs, `FLAG_GRANT_READ_URI_PERMISSION`, and system `ClipboardManager` payloads (`F17`, `F32`, `F36`).
 */
object ShareIntentInspector {

    data class CapturedSharePayload(
        val outerIntent: Intent,
        val sendIntent: Intent,
        val mimeType: String?,
        val inlineText: String?,
        val streamUri: Uri?,
        val resolvedBody: String,
        val hasReadUriPermissionFlag: Boolean,
    )

    @Suppress("DEPRECATION")
    fun captureActionSend(context: Context, activity: Activity): CapturedSharePayload {
        val raw = Shadows.shadowOf(activity).nextStartedActivity
            ?: Shadows.shadowOf(context.applicationContext as android.app.Application).nextStartedActivity
        assertNotNull("Expected outgoing Share Intent (ACTION_CHOOSER or ACTION_SEND)", raw)

        val inner: Intent = if (raw!!.action == Intent.ACTION_CHOOSER) {
            val target = raw.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
            assertNotNull("ACTION_CHOOSER missing EXTRA_INTENT", target)
            target!!
        } else {
            raw
        }

        assertTrue(
            "Expected Intent.ACTION_SEND, got ${inner.action}",
            inner.action == Intent.ACTION_SEND,
        )

        val inlineText = inner.getStringExtra(Intent.EXTRA_TEXT)
        val streamUri: Uri? = inner.getParcelableExtra(Intent.EXTRA_STREAM)
            ?: inner.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri

        val hasGrantFlag = ((inner.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0) ||
            ((raw.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0)

        val streamContent: String? = streamUri?.let { uri ->
            context.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use {
                it.readText()
            }
        }

        val body = when {
            !streamContent.isNullOrBlank() -> streamContent
            !inlineText.isNullOrBlank() -> inlineText
            else -> ""
        }
        assertTrue("Share payload body must not be empty", body.isNotBlank())

        if (streamUri != null) {
            assertTrue(
                "EXTRA_STREAM URI ($streamUri) MUST carry FLAG_GRANT_READ_URI_PERMISSION",
                hasGrantFlag,
            )
            assertTrue(
                "EXTRA_STREAM URI must use content:// FileProvider scheme, got ${streamUri.scheme}",
                streamUri.scheme == "content",
            )
        }

        return CapturedSharePayload(
            outerIntent = raw,
            sendIntent = inner,
            mimeType = inner.type,
            inlineText = inlineText,
            streamUri = streamUri,
            resolvedBody = body,
            hasReadUriPermissionFlag = hasGrantFlag,
        )
    }

    fun readClipboardText(context: Context): String {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip: ClipData? = cm.primaryClip
        assertNotNull("Expected primaryClip in ClipboardManager", clip)
        assertTrue("Expected at least 1 item in ClipboardManager", clip!!.itemCount > 0)
        return clip.getItemAt(0).coerceToText(context).toString()
    }
}
