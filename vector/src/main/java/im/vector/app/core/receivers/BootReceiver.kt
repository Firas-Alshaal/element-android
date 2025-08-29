/*
 * Copyright 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.core.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import im.vector.app.core.services.CallKeepAliveService
import timber.log.Timber

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(ctx: Context, intent: Intent) {
        Timber.w("🔁 BootReceiver: ${intent.action}")

        val appCtx = ctx.applicationContext

        // Schedule the Watchdog (safe to call multiple times)
        try {
            im.vector.app.core.watchdog.ServiceWatchdogWorker.schedule(appCtx)
            Timber.w("⏱️ Watchdog scheduled from BootReceiver")
        } catch (e: Exception) {
            Timber.e(e, "Failed scheduling Watchdog from BootReceiver")
        }

        // For LOCKED_BOOT_COMPLETED, only schedule watchdog
        if (intent.action == Intent.ACTION_LOCKED_BOOT_COMPLETED) {
            Timber.w("🔒 Device locked: schedule watchdog only, defer service start until USER_UNLOCKED")
            return
        }

        // Basic check if there's any sign of authentication before starting service
        val hasAuth = try {
            val prefs = appCtx.getSharedPreferences("vector.pref", android.content.Context.MODE_PRIVATE)
            prefs.all.isNotEmpty()
        } catch (e: Exception) {
            false
        }
        
        if (!hasAuth) {
            Timber.w("⛔ BootReceiver: No authentication signs → skip service start")
            return
        }

        // Try to start CallKeepAliveService
        try {
            val svcIntent = Intent(appCtx, CallKeepAliveService::class.java)
            ContextCompat.startForegroundService(appCtx, svcIntent)
            Timber.w("✅ KeepAlive started from BootReceiver")
        } catch (e: android.app.ForegroundServiceStartNotAllowedException) {
            // Android 15+: FGS not allowed from background at this time
            Timber.w("⚠️ FGS not allowed now (Android 15+). Will rely on kickoff/Watchdog/opening app.")
        } catch (e: Exception) {
            Timber.e(e, "❌ Failed to start KeepAlive from BootReceiver")
        }
    }
}
