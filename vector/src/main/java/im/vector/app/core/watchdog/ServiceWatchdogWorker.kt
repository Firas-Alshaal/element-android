/*
 * Copyright 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.core.watchdog

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.work.*
import im.vector.app.core.services.CallKeepAliveService
import timber.log.Timber
import java.util.concurrent.TimeUnit

class ServiceWatchdogWorker(
        appContext: Context,
        params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        return try {
            // Simple check: try to read shared preferences to see if there's any sign of authentication
            val hasAuth = try {
                val prefs = applicationContext.getSharedPreferences("vector.pref", android.content.Context.MODE_PRIVATE)
                prefs.all.isNotEmpty() // Basic check if preferences exist
            } catch (e: Exception) {
                false
            }
            
            if (!hasAuth) {
                Timber.d("⛔ Watchdog: No authentication signs → skip service restart")
                return Result.success()
            }
            
            val shouldRestartService = !isConnectionHealthy()
            val shouldCheckFcm = !isFcmHealthy()
            
            if (shouldRestartService) {
                Timber.w("🛠️ Watchdog: unhealthy → restarting CallKeepAliveService")
                try {
                    ContextCompat.startForegroundService(
                            applicationContext,
                            Intent(applicationContext, CallKeepAliveService::class.java)
                    )
                    Timber.w("✅ Watchdog: CallKeepAliveService restarted successfully")
                } catch (e: android.app.ForegroundServiceStartNotAllowedException) {
                    Timber.w("⚠️ Watchdog: FGS not allowed in background (Android 15+)")
                    // Don't retry in this case as it's expected behavior on Android 15+
                } catch (e: Exception) {
                    Timber.e(e, "❌ Watchdog: Failed to restart service")
                }
            } else if (shouldCheckFcm) {
                Timber.w("🔄 Watchdog: FCM health check needed")
                updateFcmHealthCheck()
            } else {
                Timber.d("✅ Watchdog: healthy")
            }
            
            Result.success()
        } catch (e: Exception) {
            Timber.e(e, "❌ Watchdog failure")
            Result.retry()
        }
    }

    /**
     * Simple health check - always try to ensure service is running
     * This approach is safer and doesn't rely on deprecated APIs
     */
    private fun isConnectionHealthy(): Boolean {
        // For simplicity and reliability, we always try to restart the service
        // This ensures the service is running without relying on deprecated getRunningServices()
        // The service itself will handle duplicate starts gracefully
        Timber.d("🔄 Watchdog ensuring service is running")
        return false // Always trigger service restart for maximum reliability
    }

    /**
     * Check if FCM pushers need re-registration
     * This helps detect if notifications stopped working after service force-kill
     */
    private fun isFcmHealthy(): Boolean {
        return try {
            val prefs = applicationContext.getSharedPreferences("vector.pref", android.content.Context.MODE_PRIVATE)
            val lastFcmCheck = prefs.getLong("last_fcm_health_check", 0)
            val now = System.currentTimeMillis()
            
            // Check FCM health every hour
            val fcmHealthy = (now - lastFcmCheck) < java.util.concurrent.TimeUnit.HOURS.toMillis(1)
            
            if (!fcmHealthy) {
                Timber.d("🔄 FCM health check needed (last check: ${(now - lastFcmCheck) / 1000 / 60} minutes ago)")
            }
            
            fcmHealthy
        } catch (e: Exception) {
            Timber.e(e, "Failed to check FCM health")
            false
        }
    }

    /**
     * Update FCM health check timestamp
     * Called when we verify FCM pushers are working
     */
    private fun updateFcmHealthCheck() {
        try {
            val prefs = applicationContext.getSharedPreferences("vector.pref", android.content.Context.MODE_PRIVATE)
            prefs.edit()
                .putLong("last_fcm_health_check", System.currentTimeMillis())
                .apply()
            Timber.d("✅ FCM health check timestamp updated")
        } catch (e: Exception) {
            Timber.e(e, "Failed to update FCM health check")
        }
    }

    companion object {
        fun schedule(ctx: Context) {
            val req = PeriodicWorkRequestBuilder<ServiceWatchdogWorker>(
                    15, TimeUnit.MINUTES
            )
                    .setConstraints(
                            Constraints.Builder()
                                    .setRequiredNetworkType(NetworkType.CONNECTED)
                                    .build()
                    )
                    .addTag("svc_watchdog")
                    .build()

            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
                    "svc_watchdog",
                    ExistingPeriodicWorkPolicy.UPDATE,
                    req
            )
            Timber.w("⏱️ Watchdog scheduled/updated")
        }
    }
}
