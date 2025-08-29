/*
 * Copyright 2019-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.core.di

import im.vector.app.ActiveSessionDataSource
import im.vector.app.core.dispatchers.CoroutineDispatchers
import im.vector.app.core.pushers.UnregisterUnifiedPushUseCase
import im.vector.app.core.services.GuardServiceStarter
import im.vector.app.core.session.ConfigureAndStartSessionUseCase
import im.vector.app.features.call.webrtc.WebRtcCallManager
import im.vector.app.features.crypto.keysrequest.KeyRequestHandler
import im.vector.app.features.crypto.verification.IncomingVerificationRequestHandler
import im.vector.app.features.notifications.PushRuleTriggerListener
import im.vector.app.features.session.SessionListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.matrix.android.sdk.api.auth.AuthenticationService
import org.matrix.android.sdk.api.session.Session
import org.matrix.android.sdk.api.util.Optional
import org.matrix.android.sdk.api.util.toOption
import timber.log.Timber
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import im.vector.app.core.login.PostLoginAction
import im.vector.app.core.pushers.PushersManager
import im.vector.app.core.services.CallKeepAliveService
import im.vector.app.features.mdm.MdmData
import im.vector.app.features.mdm.MdmService

@Singleton
class ActiveSessionHolder @Inject constructor(
        @dagger.hilt.android.qualifiers.ApplicationContext
        private val context: Context,
        private val activeSessionDataSource: ActiveSessionDataSource,
        private val keyRequestHandler: KeyRequestHandler,
        private val incomingVerificationRequestHandler: IncomingVerificationRequestHandler,
        private val callManager: WebRtcCallManager,
        private val pushRuleTriggerListener: PushRuleTriggerListener,
        private val sessionListener: SessionListener,
        private val imageManager: ImageManager,
        private val guardServiceStarter: GuardServiceStarter,
        private val sessionInitializer: SessionInitializer,
        private val authenticationService: AuthenticationService,
        private val configureAndStartSessionUseCase: ConfigureAndStartSessionUseCase,
        private val unregisterUnifiedPushUseCase: UnregisterUnifiedPushUseCase,
        private val applicationCoroutineScope: CoroutineScope,
        private val coroutineDispatchers: CoroutineDispatchers,
) {

    @Volatile private var keepAliveStarted = false

    private var activeSessionReference: AtomicReference<Session?> = AtomicReference()

    fun setActiveSession(session: Session) {
        Timber.w("setActiveSession of ${session.myUserId}")
        activeSessionReference.set(session)
        activeSessionDataSource.post(session.toOption())

        keyRequestHandler.start(session)
        incomingVerificationRequestHandler.start(session)
        session.addListener(sessionListener)
        pushRuleTriggerListener.startWithSession(session)
        session.callSignalingService().addCallListener(callManager)
        imageManager.onSessionStarted(session)
        guardServiceStarter.start()

        // **SESSION-BASED SERVICE**: Start keep-alive service when user logs in
        startKeepAliveService(session)
        im.vector.app.core.watchdog.ServiceWatchdogWorker.schedule(context)
        Timber.w("ActiveSessionHolder: session=${session.myUserId} → keepAlive + watchdog scheduled")
    }

    suspend fun clearActiveSession() {
        // Do some cleanup first
        getSafeActiveSession()?.let {
            Timber.w("clearActiveSession of ${it.myUserId}")
            it.callSignalingService().removeCallListener(callManager)
            it.removeListener(sessionListener)

            // **SESSION-BASED SERVICE**: Stop keep-alive service when user logs out
            stopKeepAliveService(it)
        }

        activeSessionReference.set(null)
        activeSessionDataSource.post(Optional.empty())

        keyRequestHandler.stop()
        incomingVerificationRequestHandler.stop()
        pushRuleTriggerListener.stop()
        // No need to unregister the pusher, the sign out will (should?) do it server side.
        unregisterUnifiedPushUseCase.execute(pushersManager = null)
        guardServiceStarter.stop()

        androidx.work.WorkManager.getInstance(context)
                .cancelUniqueWork("svc_watchdog")
    }

    fun hasActiveSession(): Boolean {
        return activeSessionReference.get() != null || authenticationService.hasAuthenticatedSessions()
    }

    fun getSafeActiveSession(): Session? {
        return runBlocking { getOrInitializeSession() }
    }

    suspend fun getSafeActiveSessionSuspend(): Session? {
        return getOrInitializeSession()
    }

    fun getSafeActiveSessionAsync(withSession: ((Session?) -> Unit)) {
        applicationCoroutineScope.launch(coroutineDispatchers.io) {
            val session = getOrInitializeSession()
            withSession(session)
        }
    }

    fun getActiveSession(): Session {
        return getSafeActiveSession()
                ?: throw IllegalStateException("You should authenticate before using this")
    }

    suspend fun getOrInitializeSession(): Session? {
        return activeSessionReference.get()
                ?: sessionInitializer.tryInitialize(readCurrentSession = { activeSessionReference.get() }) { session ->
                    setActiveSession(session)
                    configureAndStartSessionUseCase.execute(session, startSyncing = false)
                }
    }

    fun isWaitingForSessionInitialization() = activeSessionReference.get() == null && authenticationService.hasAuthenticatedSessions()

    /**
     * **SESSION-BASED SERVICE**: Start keep-alive service when user logs in
     */
    @Synchronized
    private fun startKeepAliveService(session: Session) {
        try {
            if (!keepAliveStarted) {
                keepAliveStarted = true
                val intent = Intent(context, CallKeepAliveService::class.java)
                ContextCompat.startForegroundService(context, intent)
                Timber.w("🔐 Keep-alive service started for user login: ${session.myUserId}")

                // **FCM RECOVERY**: Ensure pushers are registered when service starts
                // This is critical for proper notification delivery after login
//                ensureFcmPushersRegistered()
            } else {
                Timber.d("Keep-alive already started; skipping duplicate start")
            }
        } catch (e: Exception) {
            Timber.e(e, "❌ Failed to start keep-alive service for user login: ${session.myUserId}")
            keepAliveStarted = false
        }
    }

    /**
     * Ensure FCM pushers are registered when service starts
     * This prevents notification issues after service restart
     */
    private fun ensureFcmPushersRegistered() {
        try {
            // Use reflection to call VectorApplication method safely
            val app = context.applicationContext
            if (app.javaClass.simpleName.contains("VectorApplication")) {
                val method = app.javaClass.getDeclaredMethod("ensureFcmTokenAndPushersAreRegistered")
                method.isAccessible = true
                method.invoke(app)
                Timber.d("✅ FCM pushers registration triggered from ActiveSessionHolder")
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to trigger FCM pushers registration from ActiveSessionHolder")
        }
    }

    /**
     * **SESSION-BASED SERVICE**: Stop keep-alive service when user logs out
     */
    private fun stopKeepAliveService(session: Session) {
        try {
            val intent = Intent(context, CallKeepAliveService::class.java)
            context.stopService(intent)
            keepAliveStarted = false
            Timber.w("🚪 Keep-alive service stopped for user logout: ${session.myUserId}")
        } catch (e: Exception) {
            Timber.e(e, "❌ Failed to stop keep-alive service for user logout: ${session.myUserId}")
        }
    }

    // TODO Stop sync ?
//    fun switchToSession(sessionParams: SessionParams) {
//        val newActiveSession = authenticationService.getSession(sessionParams)
//        activeSession.set(newActiveSession)
//    }
}
