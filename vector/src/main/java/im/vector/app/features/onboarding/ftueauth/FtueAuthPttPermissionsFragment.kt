/*
 * Copyright 2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.onboarding.ftueauth

import android.Manifest
import android.animation.AnimatorInflater
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.vanniktech.ui.hideKeyboard
import dagger.hilt.android.AndroidEntryPoint
import im.vector.app.R
import im.vector.app.core.extensions.hideKeyboard
import im.vector.app.core.utils.checkPermissions
import im.vector.app.core.utils.isIgnoringBatteryOptimizations
import im.vector.app.core.utils.registerForPermissionsResult
import im.vector.app.core.utils.requestDisablingBatteryOptimization
import im.vector.app.core.utils.toast
import im.vector.app.databinding.FragmentFtueAuthPttPermissionsBinding
import im.vector.app.features.onboarding.OnboardingAction
import im.vector.app.features.settings.VectorPreferences
import im.vector.lib.strings.CommonStrings
import im.vector.app.core.di.ActiveSessionHolder
import im.vector.app.core.pushers.PushersManager
import im.vector.app.core.pushers.EnsureFcmTokenIsRetrievedUseCase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import javax.inject.Inject

@AndroidEntryPoint
class FtueAuthPttPermissionsFragment : AbstractFtueAuthFragment<FragmentFtueAuthPttPermissionsBinding>() {

    @Inject lateinit var vectorPreferences: VectorPreferences
    @Inject lateinit var activeSessionHolder: ActiveSessionHolder
    @Inject lateinit var pushersManager: PushersManager
    @Inject lateinit var ensureFcmTokenIsRetrievedUseCase: EnsureFcmTokenIsRetrievedUseCase

    private lateinit var permissionsAdapter: PttPermissionsAdapter

    // Coroutine scope for push notifications setup - bounded to fragment lifecycle
    private val pushSetupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    data class PttPermissionItem(
        val permission: String,
        val title: String,
        val description: String,
        val icon: Int,
        val isRequired: Boolean = true,
        var isGranted: Boolean = false
    )

    private val pttPermissions = listOf(
        PttPermissionItem(
            permission = Manifest.permission.RECORD_AUDIO,
            title = "Record Audio",
            description = "Talk to people via push-to-talk",
            icon = R.drawable.ic_microphone_on,
            isRequired = true
        ),
        PttPermissionItem(
            permission = Manifest.permission.POST_NOTIFICATIONS,
            title = "Send Notifications",
            description = "Be notified when you receive messages",
            icon = R.drawable.ic_notification,
            isRequired = true
        ),
        PttPermissionItem(
            permission = Manifest.permission.BLUETOOTH_CONNECT,
            title = "Connect to Nearby Devices",
            description = "Connect accessories such as headsets and microphones",
            icon = R.drawable.ic_bluetooth,
            isRequired = false
        ),
        PttPermissionItem(
            permission = "BATTERY_OPTIMIZATION",
            title = "Run in Background",
            description = "Ensures that you receive messages when the screen is off",
            icon = R.drawable.ic_battery_charging,
            isRequired = true
        ),
        PttPermissionItem(
            permission = Manifest.permission.ACCESS_FINE_LOCATION,
            title = "Location Sharing",
            description = "Share your location with contacts",
            icon = R.drawable.ic_location_pin,
            isRequired = false
        ),
        PttPermissionItem(
            permission = Manifest.permission.MANAGE_OWN_CALLS,
            title = "Make & Manage Phone Calls",
            description = "Prevents voice messages from interfering with phone calls",
            icon = R.drawable.ic_call,
            isRequired = false
        ),
        PttPermissionItem(
            permission = Manifest.permission.SYSTEM_ALERT_WINDOW,
            title = "Display Over Other Apps",
            description = "Show PTT controls over other apps for quick access",
            icon = R.drawable.ic_overlay,
            isRequired = false
        )
    )

    private val permissionResultLauncher = registerForPermissionsResult { _, deniedPermanently ->
        if (deniedPermanently) {
            // Show settings dialog for denied permissions
        }
        updatePermissionStates()
        updateContinueButtonState()
    }

    // Activity Result Launcher for battery optimization and overlay permissions
    private val specialPermissionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        // Update permission states after returning from system settings
        updatePermissionStates()
        updateContinueButtonState()
    }

    override fun getBinding(inflater: LayoutInflater, container: ViewGroup?): FragmentFtueAuthPttPermissionsBinding {
        return FragmentFtueAuthPttPermissionsBinding.inflate(inflater, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupViews()
        updatePermissionStates()
        updateContinueButtonState()
    }

    private fun setupViews() {
        requireActivity().hideKeyboard()

        // Setup RecyclerView
        permissionsAdapter = PttPermissionsAdapter(pttPermissions.toMutableList()) { permission ->
            requestSinglePermission(permission)
        }

        views.permissionsRecyclerView.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = permissionsAdapter
        }

        // Setup Continue Button
        views.continueButton.setOnClickListener {
            onContinueClicked()
        }

        // Start pulse animation
        startPulseAnimation()
    }

    private fun startPulseAnimation() {
        // Start pulse animation for ring1 (immediate start)
        val pulseAnimation1 = AnimatorInflater.loadAnimator(requireContext(), R.animator.pulse_animation)
        pulseAnimation1.setTarget(views.pulseMicrophoneRing1)
        pulseAnimation1.start()

        // Start pulse animation for ring2 (delayed start for wave effect)
        val pulseAnimation2 = AnimatorInflater.loadAnimator(requireContext(), R.animator.pulse_animation)
        pulseAnimation2.setTarget(views.pulseMicrophoneRing2)
        pulseAnimation2.startDelay = 1000 // 1 second delay
        pulseAnimation2.start()
    }

    private fun requestSinglePermission(permission: String) {
        // Add debug logging to see what permission was clicked
        timber.log.Timber.d("PTT Permissions: Requesting permission: $permission")

        when (permission) {
            "BATTERY_OPTIMIZATION" -> {
                timber.log.Timber.d("PTT Permissions: Opening battery optimization settings")
                requestBatteryOptimizationExemption()
            }
            Manifest.permission.SYSTEM_ALERT_WINDOW -> {
                timber.log.Timber.d("PTT Permissions: Opening overlay permission settings")
                requestSystemAlertWindowPermission()
            }
            else -> {
                timber.log.Timber.d("PTT Permissions: Requesting normal permission: $permission")
                // Handle normal Android permission
                if (!checkPermissions(listOf(permission), requireActivity(), permissionResultLauncher)) {
                    // Permission not granted, will be handled by launcher
                }
            }
        }
    }

    private fun requestBatteryOptimizationExemption() {
        timber.log.Timber.d("PTT Permissions: requestBatteryOptimizationExemption called")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                // Use the existing proven function
                requestDisablingBatteryOptimization(requireActivity(), specialPermissionLauncher)
                timber.log.Timber.d("PTT Permissions: Launched battery optimization request successfully")
            } catch (e: Exception) {
                timber.log.Timber.e(e, "PTT Permissions: Exception launching battery optimization intent")
                requireContext().toast("Error opening battery settings: ${e.message}")
                // Try fallback
                openBatteryOptimizationFallback()
            }
        } else {
            requireContext().toast("Battery optimization not needed on this Android version")
        }
    }

    private fun openBatteryOptimizationFallback() {
        try {
            // Try battery optimization settings page
            val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            if (intent.resolveActivity(requireContext().packageManager) != null) {
                specialPermissionLauncher.launch(intent)
            } else {
                // Try general application settings
                openAppSettings()
            }
        } catch (e: Exception) {
            openAppSettings()
        }
    }

    private fun openAppSettings() {
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${requireContext().packageName}")
            }
            specialPermissionLauncher.launch(intent)
            requireContext().toast("Please find Battery Optimization settings and disable for this app")
        } catch (e: Exception) {
            requireContext().toast("Please manually disable battery optimization for this app in Settings")
        }
    }

    private fun requestSystemAlertWindowPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
                data = Uri.parse("package:${requireContext().packageName}")
            }
            specialPermissionLauncher.launch(intent)
        }
    }

    private fun updatePermissionStates() {
        pttPermissions.forEach { permissionItem ->
            permissionItem.isGranted = when (permissionItem.permission) {
                "BATTERY_OPTIMIZATION" -> {
                    requireContext().isIgnoringBatteryOptimizations()
                }
                Manifest.permission.SYSTEM_ALERT_WINDOW -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        Settings.canDrawOverlays(requireContext())
                    } else {
                        true // Permission not needed on older versions
                    }
                }
                else -> {
                    ContextCompat.checkSelfPermission(
                        requireContext(),
                        permissionItem.permission
                    ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                }
            }
        }
        permissionsAdapter.updatePermissions(pttPermissions)
    }

    private fun updateContinueButtonState() {
        val requiredPermissions = pttPermissions.filter { it.isRequired }
        val allRequiredGranted = requiredPermissions.all { it.isGranted }

        views.continueButton.isEnabled = allRequiredGranted
        views.continueButton.alpha = if (allRequiredGranted) 1.0f else 0.5f
    }

    private fun onContinueClicked() {
        // Mark that permissions screen has been shown
        vectorPreferences.setHasShownPttPermissions(true)

        // 🎯 CRITICAL FIX: Force push notifications setup for new users
        forcePushNotificationsSetup()

        // Continue to next screen (splash)
        viewModel.handle(OnboardingAction.SplashAction.OnPermissionsComplete)
    }

    /**
     * Force push notifications setup for new users to ensure immediate notification delivery.
     * This addresses the issue where new users don't receive PTT notifications until
     * they manually restart the app.
     *
     * Compatible with all Android versions and handles edge cases gracefully.
     */
    private fun forcePushNotificationsSetup() {
        timber.log.Timber.d("🔔 PTT Permissions: Starting forced push notifications setup for new user")

        pushSetupScope.launch {
            try {
                // Step 1: Ensure notifications are enabled at device level
                if (!vectorPreferences.areNotificationEnabledForDevice()) {
                    timber.log.Timber.w("🔔 Notifications were disabled, enabling for device")
                    // Safe to enable - this is what the user expects for PTT
                    vectorPreferences.setNotificationEnabledForDevice(true)
                }

                // Step 2: Wait a moment for any ongoing session initialization to complete
                delay(1000) // 1 second delay to ensure session stability

                // Step 3: Check if we have an active session (most critical check)
                if (!activeSessionHolder.hasActiveSession()) {
                    timber.log.Timber.w("🔔 No active session found yet, push setup will happen when session becomes active")
                    return@launch
                }

                val session = activeSessionHolder.getSafeActiveSession()
                if (session == null) {
                    timber.log.Timber.w("🔔 Active session is null, cannot setup push notifications")
                    return@launch
                }

                timber.log.Timber.d("🔔 Active session found: ${session.myUserId}")

                // Step 4: Force FCM token retrieval and pusher registration
                try {
                    ensureFcmTokenIsRetrievedUseCase.execute(
                        pushersManager = pushersManager,
                        registerPusher = true // Force registration even if pusher exists
                    )
                    timber.log.Timber.d("🔔 FCM token retrieval initiated")
                } catch (e: Exception) {
                    timber.log.Timber.e(e, "🔔 Error during FCM token retrieval, trying alternative approach")
                }

                // Step 5: Force pushers refresh to ensure server knows about this device
                try {
                    session.pushersService().refreshPushers()
                    timber.log.Timber.d("🔔 Pushers refreshed for session: ${session.myUserId}")
                } catch (e: Exception) {
                    timber.log.Timber.e(e, "🔔 Error refreshing pushers")
                }

                // Step 6: Additional delay and verification check
                delay(2000) // 2 seconds delay to allow server communication

                try {
                    val pushers = session.pushersService().getPushers()
                    timber.log.Timber.d("🔔 Current pushers count: ${pushers.size}")

                    if (pushers.isEmpty()) {
                        timber.log.Timber.w("🔔 No pushers found after setup, will retry in background")
                        // Schedule a background retry
                        scheduleBackgroundPushSetup()
                    } else {
                        timber.log.Timber.d("🔔 Push notifications setup completed successfully")
                    }
                } catch (e: Exception) {
                    timber.log.Timber.e(e, "🔔 Error verifying pushers setup")
                }

            } catch (e: Exception) {
                timber.log.Timber.e(e, "🔔 Critical error during push notifications setup")
                // Don't crash the app, just log the error
            }
        }
    }

    /**
     * Schedule a background retry for push setup in case the initial attempt failed.
     * This ensures we don't miss push notifications even in edge cases.
     */
    private fun scheduleBackgroundPushSetup() {
        pushSetupScope.launch {
            try {
                // Wait longer and try again
                delay(10000) // 10 seconds delay

                if (activeSessionHolder.hasActiveSession()) {
                    val session = activeSessionHolder.getSafeActiveSession()
                    session?.let {
                        timber.log.Timber.d("🔔 Background push setup retry")
                        ensureFcmTokenIsRetrievedUseCase.execute(pushersManager, true)
                        it.pushersService().refreshPushers()
                    }
                }
            } catch (e: Exception) {
                timber.log.Timber.e(e, "🔔 Background push setup retry failed")
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        // Cancel any ongoing push setup operations to prevent memory leaks
        try {
            pushSetupScope.coroutineContext.job.cancel()
            timber.log.Timber.d("🔔 Push setup coroutines cancelled")
        } catch (e: Exception) {
            // Ignore cancellation errors
        }
    }

    override fun resetViewModel() {
        // Nothing to do
    }
}
