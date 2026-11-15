package com.a55.wifiradar.core.wifiplatform

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.RemoteException
import kotlin.coroutines.cancellation.CancellationException

/**
 * Defensive IPC wrapper (`F34`) that ALWAYS rethrows [CancellationException] to preserve Kotlin
 * structured concurrency before isolating [SecurityException], [RemoteException],
 * [IllegalStateException], [IllegalArgumentException], and [RuntimeException] (including
 * `DeadSystemRuntimeException`).
 */
internal inline fun <T> safeIpcCall(
    default: T,
    noinline onSecurityException: ((SecurityException) -> Unit)? = null,
    block: () -> T,
): T {
    return try {
        block()
    } catch (ce: CancellationException) {
        throw ce
    } catch (se: SecurityException) {
        onSecurityException?.invoke(se)
        default
    } catch (_: RemoteException) {
        default
    } catch (_: IllegalStateException) {
        default
    } catch (_: IllegalArgumentException) {
        default
    } catch (_: RuntimeException) {
        default
    }
}

internal inline fun safeIpcUnit(
    noinline onSecurityException: ((SecurityException) -> Unit)? = null,
    block: () -> Unit,
) {
    safeIpcCall(Unit, onSecurityException, block)
}

/**
 * Registers a system OS [BroadcastReceiver] safely across API 30–35:
 * - On API 33+ (`TIRAMISU`+): passes `Context.RECEIVER_NOT_EXPORTED` via `@RequiresApi(33)` so
 *   Android 14/15 `targetSdk = 35` flag enforcement succeeds with zero exported surface.
 * - On API 30–32: invokes 2-arg `registerReceiver(receiver, filter)` directly, avoiding
 *   `ContextCompat`'s `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` crash on API 30–32.
 */
internal fun Context.registerSystemBroadcastReceiverCompat(
    receiver: BroadcastReceiver,
    filter: IntentFilter,
    sdkInt: Int = Build.VERSION.SDK_INT,
): Intent? = safeIpcCall(default = null) {
    if (sdkInt >= Build.VERSION_CODES.TIRAMISU) {
        Api33PlatformCompat.registerNotExportedReceiver(this, receiver, filter)
    } else {
        @Suppress("UnspecifiedRegisterReceiverFlag")
        registerReceiver(receiver, filter)
    }
}
