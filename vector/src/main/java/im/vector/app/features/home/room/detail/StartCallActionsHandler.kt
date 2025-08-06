/*
 * Copyright 2021-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.room.detail

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.ActivityResultLauncher
import androidx.fragment.app.Fragment
import com.airbnb.lottie.LottieAnimationView
import com.airbnb.mvrx.withState
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import im.vector.app.R
import im.vector.app.core.utils.PERMISSIONS_FOR_AUDIO_IP_CALL
import im.vector.app.core.utils.PERMISSIONS_FOR_VIDEO_IP_CALL
import im.vector.app.core.utils.checkPermissions
import im.vector.app.features.call.webrtc.WebRtcCallManager
import im.vector.app.features.home.room.detail.composer.MessageComposerViewModel
import im.vector.app.features.home.room.detail.composer.PttManager
import im.vector.app.features.home.room.detail.composer.PttTcpReceiverService
import im.vector.app.features.settings.VectorPreferences
import im.vector.lib.strings.CommonStrings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.matrix.android.sdk.api.session.Session
import org.matrix.android.sdk.api.session.getRoom
import timber.log.Timber

class StartCallActionsHandler(
        private val roomId: String,
        private val fragment: Fragment,
        private val callManager: WebRtcCallManager,
        private val vectorPreferences: VectorPreferences,
        private val timelineViewModel: TimelineViewModel,
        private val messageComposerViewModel: MessageComposerViewModel,
        private val startCallActivityResultLauncher: ActivityResultLauncher<Array<String>>,
        private val showDialogWithMessage: (String) -> Unit,
        private val onTapToReturnToCall: () -> Unit,
        private val myUserId: String, // <-- add this
        private val session: Session,
        private val pttManager: PttManager = PttManager(context = fragment.requireContext(), session = session)
) {

    fun onVideoCallClicked() {
        handleCallRequest(true)
    }

    fun onVoiceCallClicked() {
        handleCallRequest(false)
    }

    private fun sendPttStatus(status: String) {
        val content = mapOf(
                "status" to status,
                "userId" to myUserId
        )

        val room = session.getRoom(roomId) ?: return

        CoroutineScope(Dispatchers.IO).launch {
            try {
                room.stateService().sendStateEvent("ptt.status", myUserId, content)
            } catch (e: Exception) {
                Timber.e(e, "❌ Failed to send ptt.status")
            }
        }
    }

    @SuppressLint("InflateParams", "ClickableViewAccessibility")
    fun onVoiceSoundClicked() {
        val dialogView = fragment.layoutInflater.inflate(R.layout.dialog_push_to_talk, null)
        val dialog = Dialog(fragment.requireContext())
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT)) // Make background transparent
        dialog.setContentView(dialogView)

        (fragment as? TimelineFragment)?.isPushToTalkDialogShowing = true


        dialog.setOnShowListener {
            val btnRecord = dialogView.findViewById<View>(R.id.btn_record)
            val waveAnimation = dialogView.findViewById<LottieAnimationView>(R.id.wave_animation)

            // Get the VoiceMessageRecorderView to trigger existing recording functions
//            val voiceMessageRecorderView = fragment.view?.findViewById<VoiceMessageRecorderView>(R.id.voiceMessageRecorderView)

            btnRecord.setOnTouchListener { _, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        // Start recording using the existing function
//                        voiceMessageRecorderView?.callback?.onVoiceRecordingStarted()
                        // Stop any existing receiver service immediately
                        val stopIntent = Intent(fragment.requireContext(), PttTcpReceiverService::class.java).apply {
                            putExtra("roomId", roomId)
                        }
                        fragment.requireContext().stopService(stopIntent)
                        
                        // ✅ Use coordinated PTT to fix audio timing
                        CoroutineScope(Dispatchers.IO).launch {
                            try {
                                pttManager.startStreamingCoordinated(roomId)
                            } catch (e: Exception) {
                                Timber.e(e, "❌ Failed to start coordinated PTT")
                            }
                        }
                        waveAnimation.visibility = View.VISIBLE
                        waveAnimation.playAnimation()
                        true
                    }

                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        // Stop recording and send the voice message using the existing function
//                        voiceMessageRecorderView?.callback?.onVoiceRecordingEnded()
                        // ✅ Use coordinated PTT stop
                        CoroutineScope(Dispatchers.IO).launch {
                            try {
                                pttManager.stopStreamingCoordinated(roomId)
                            } catch (e: Exception) {
                                Timber.e(e, "❌ Failed to stop coordinated PTT")
                                // Fallback to old method
                                pttManager.stopStreaming()
                        sendPttStatus("idle")
                            }
                        }
                        waveAnimation.pauseAnimation()
                        waveAnimation.visibility = View.GONE
                        true
                    }
                    else -> false
                }
            }
        }

        dialog.setOnDismissListener {
            // Reset flag when dialog is closed
            (fragment as? TimelineFragment)?.isPushToTalkDialogShowing = false
        }

        dialog.show()
        dialog.window?.setLayout(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    private fun handleCallRequest(isVideoCall: Boolean) = withState(timelineViewModel) { state ->
        if (state.hasActiveElementCallWidget() && !isVideoCall) {
            timelineViewModel.handle(RoomDetailAction.OpenElementCallWidget)
            return@withState
        }

        val roomSummary = state.asyncRoomSummary.invoke() ?: return@withState
        when (roomSummary.joinedMembersCount) {
            1 -> {
                val pendingInvite = roomSummary.invitedMembersCount ?: 0 > 0
                if (pendingInvite) {
                    // wait for other to join
                    showDialogWithMessage(fragment.getString(CommonStrings.cannot_call_yourself_with_invite))
                } else {
                    // You cannot place a call with yourself.
                    showDialogWithMessage(fragment.getString(CommonStrings.cannot_call_yourself))
                }
            }
            2 -> {
                val currentCall = callManager.getCurrentCall()
                if (currentCall?.signalingRoomId == roomId) {
                    onTapToReturnToCall()
                } else if (!state.isAllowedToStartWebRTCCall) {
                    showDialogWithMessage(
                            fragment.getString(
                                    if (state.isDm()) {
                                        CommonStrings.no_permissions_to_start_webrtc_call_in_direct_room
                                    } else {
                                        CommonStrings.no_permissions_to_start_webrtc_call
                                    }
                            )
                    )
                } else {
                    safeStartCall(isVideoCall)
                }
            }
            else -> {
                // it's jitsi call
                // can you add widgets??
                if (!state.isAllowedToManageWidgets) {
                    // You do not have permission to start a conference call in this room
                    showDialogWithMessage(
                            fragment.getString(
                                    if (state.isDm()) {
                                        CommonStrings.no_permissions_to_start_conf_call_in_direct_room
                                    } else {
                                        CommonStrings.no_permissions_to_start_conf_call
                                    }
                            )
                    )
                } else {
                    if (state.hasActiveJitsiWidget()) {
                        // A conference is already in progress, return
                    } else {
                        MaterialAlertDialogBuilder(fragment.requireContext())
                                .setTitle(if (isVideoCall) CommonStrings.video_meeting else CommonStrings.audio_meeting)
                                .setMessage(CommonStrings.audio_video_meeting_description)
                                .setPositiveButton(fragment.getString(CommonStrings.create)) { _, _ ->
                                    // create the widget, then navigate to it..
                                    timelineViewModel.handle(RoomDetailAction.AddJitsiWidget(isVideoCall))
                                }
                                .setNegativeButton(fragment.getString(CommonStrings.action_cancel), null)
                                .show()
                    }
                }
            }
        }
    }

    private fun safeStartCall(isVideoCall: Boolean) {
        if (vectorPreferences.preventAccidentalCall()) {
            MaterialAlertDialogBuilder(fragment.requireActivity())
                    .setMessage(if (isVideoCall) CommonStrings.start_video_call_prompt_msg else CommonStrings.start_voice_call_prompt_msg)
                    .setPositiveButton(if (isVideoCall) CommonStrings.start_video_call else CommonStrings.start_voice_call) { _, _ ->
                        safeStartCall2(isVideoCall)
                    }
                    .setNegativeButton(CommonStrings.action_cancel, null)
                    .show()
        } else {
            safeStartCall2(isVideoCall)
        }
    }

    private fun safeStartCall2(isVideoCall: Boolean) {
        val startCallAction = RoomDetailAction.StartCall(isVideoCall)
        timelineViewModel.pendingAction = startCallAction
        if (isVideoCall) {
            if (checkPermissions(
                            PERMISSIONS_FOR_VIDEO_IP_CALL,
                            fragment.requireActivity(),
                            startCallActivityResultLauncher,
                            CommonStrings.permissions_rationale_msg_camera_and_audio
                    )) {
                timelineViewModel.pendingAction = null
                timelineViewModel.handle(startCallAction)
            }
        } else {
            if (checkPermissions(
                            PERMISSIONS_FOR_AUDIO_IP_CALL,
                            fragment.requireActivity(),
                            startCallActivityResultLauncher,
                            CommonStrings.permissions_rationale_msg_record_audio
                    )) {
                timelineViewModel.pendingAction = null
                timelineViewModel.handle(startCallAction)
            }
        }
    }
}
