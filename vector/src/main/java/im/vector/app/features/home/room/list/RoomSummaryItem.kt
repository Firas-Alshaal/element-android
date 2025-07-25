/*
 * Copyright 2019-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.room.list

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import androidx.core.view.isInvisible
import androidx.core.view.isVisible
import com.airbnb.epoxy.EpoxyAttribute
import com.airbnb.epoxy.EpoxyModelClass
import com.airbnb.lottie.LottieAnimationView
import com.amulyakhare.textdrawable.TextDrawable
import im.vector.app.R
import im.vector.app.core.epoxy.ClickListener
import im.vector.app.core.epoxy.VectorEpoxyHolder
import im.vector.app.core.epoxy.VectorEpoxyModel
import im.vector.app.core.epoxy.onClick
import im.vector.app.core.extensions.setTextOrHide
import im.vector.app.core.ui.views.PresenceStateImageView
import im.vector.app.core.ui.views.ShieldImageView
import im.vector.app.features.displayname.getBestName
import im.vector.app.features.home.AvatarRenderer
import im.vector.app.features.home.RoomListDisplayMode
import im.vector.app.features.themes.ThemeUtils
import im.vector.lib.core.utils.epoxy.charsequence.EpoxyCharSequence
import org.matrix.android.sdk.api.session.crypto.model.RoomEncryptionTrustLevel
import org.matrix.android.sdk.api.session.presence.model.UserPresence
import org.matrix.android.sdk.api.util.MatrixItem
import timber.log.Timber

@EpoxyModelClass
abstract class RoomSummaryItem : VectorEpoxyModel<RoomSummaryItem.Holder>(R.layout.item_room) {

    @EpoxyAttribute
    lateinit var typingMessage: String

    @EpoxyAttribute
    lateinit var avatarRenderer: AvatarRenderer

    @EpoxyAttribute
    lateinit var matrixItem: MatrixItem

    @EpoxyAttribute
    var displayMode: RoomListDisplayMode = RoomListDisplayMode.PEOPLE

    @EpoxyAttribute
    lateinit var subtitle: String

    @EpoxyAttribute
    lateinit var lastFormattedEvent: EpoxyCharSequence

    @EpoxyAttribute
    lateinit var lastEventTime: String

    @EpoxyAttribute
    var encryptionTrustLevel: RoomEncryptionTrustLevel? = null

    @EpoxyAttribute
    var userPresence: UserPresence? = null

    @EpoxyAttribute
    var showPresence: Boolean = false

    @EpoxyAttribute
    var izPublic: Boolean = false

    @EpoxyAttribute
    var unreadNotificationCount: Int = 0

    @EpoxyAttribute
    var hasUnreadMessage: Boolean = false

    @EpoxyAttribute
    var hasDraft: Boolean = false

    @EpoxyAttribute
    var showHighlighted: Boolean = false

    @EpoxyAttribute
    var hasFailedSending: Boolean = false

    @EpoxyAttribute(EpoxyAttribute.Option.DoNotHash)
    var itemLongClickListener: View.OnLongClickListener? = null

    @EpoxyAttribute(EpoxyAttribute.Option.DoNotHash)
    var itemClickListener: ClickListener? = null

    @EpoxyAttribute
    var showSelected: Boolean = false

    @EpoxyAttribute
    var useSingleLineForLastEvent: Boolean = false

    @EpoxyAttribute(EpoxyAttribute.Option.DoNotHash)
    var pttClickListener: ((action: String, roomId: String) -> Unit)? = null

    @EpoxyAttribute(EpoxyAttribute.Option.DoNotHash)
    var listener: RoomListListener? = null

    @SuppressLint("ClickableViewAccessibility")
    override fun bind(holder: Holder) {
        super.bind(holder)

        renderDisplayMode(holder)
        holder.rootView.onClick(itemClickListener)
        holder.rootView.setOnLongClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            itemLongClickListener?.onLongClick(it) ?: false
        }
        holder.titleView.text = matrixItem.getBestName()
        holder.unreadCounterBadgeView.render(UnreadCounterBadgeView.State.Count(unreadNotificationCount, showHighlighted))
        holder.unreadIndentIndicator.isVisible = hasUnreadMessage
        holder.draftView.isVisible = hasDraft
        avatarRenderer.render(matrixItem, holder.avatarImageView)
        holder.roomAvatarDecorationImageView.render(encryptionTrustLevel)
        holder.roomAvatarPublicDecorationImageView.isVisible = izPublic
        holder.roomAvatarFailSendingImageView.isVisible = hasFailedSending
        renderSelection(holder, showSelected)
        holder.roomAvatarPresenceImageView.render(showPresence, userPresence)
        holder.view.findViewById<Button>(R.id.btn_record_original_outside).setOnTouchListener { button, event ->
            val waveAnimation = holder.view.findViewById<LottieAnimationView>(R.id.wave_animation_original_outside)
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    if (!hasMicPermission(holder.view.context)) {
                        listener?.requestVoicePermission(holder.view.context) { granted ->
                            // Do nothing here! Just let the user press again after granting.
                            if (!granted) {
                                Toast.makeText(holder.view.context, "Microphone permission denied", Toast.LENGTH_SHORT).show()
                                button.isPressed = false
                            }
                        }
                        return@setOnTouchListener true
                    }

                    // If we reach here, mic permission is already granted
                    listener?.checkPttPermissionAndStart(matrixItem.id) { hasSynapsePermission ->
                        if (!hasSynapsePermission) {
                            // Reset button state if Synapse permission denied
                            button.isPressed = false
                            return@checkPttPermissionAndStart
                        }

                        // Only start PTT if both permissions are granted
                        listener?.onStartPtt(matrixItem.id)
                        waveAnimation.apply {
                            if (visibility != View.VISIBLE) visibility = View.VISIBLE
                            if (!isAnimating) playAnimation()
                        }
                    }
                    return@setOnTouchListener true // <-- ADD THIS LINE
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    Timber.d("📢 PTT Touch Up/Cancel on: ${matrixItem.id}")
                    // Only stop PTT if we actually started it (animation is visible)
                    if (waveAnimation.visibility == View.VISIBLE) {
                        pttClickListener?.invoke("ACTION_UP", matrixItem.id)
                    }
                    waveAnimation.apply {
                        if (isAnimating) pauseAnimation()
                        if (visibility != View.GONE) visibility = View.GONE
                    }
                    true // Consume the touch event
                }

                else -> false
            }
        }
        if (useSingleLineForLastEvent) {
            holder.subtitleView.setLines(1)
        }
    }

    private fun startPtt(roomId: String, waveAnimation: LottieAnimationView) {
        Timber.d("📢 PTT Touch Down on: $roomId")
        pttClickListener?.invoke("ACTION_DOWN", roomId)
        waveAnimation.apply {
            if (visibility != View.VISIBLE) visibility = View.VISIBLE
            if (!isAnimating) playAnimation()
        }
    }

    private fun renderDisplayMode(holder: Holder) = when (displayMode) {
        RoomListDisplayMode.ROOMS,
        RoomListDisplayMode.PEOPLE,
        RoomListDisplayMode.NOTIFICATIONS -> renderForDefaultDisplayMode(holder)
        RoomListDisplayMode.FILTERED -> renderForFilteredDisplayMode(holder)
    }

    private fun renderForDefaultDisplayMode(holder: Holder) {
        holder.subtitleView.text = lastFormattedEvent.charSequence
        holder.lastEventTimeView.text = lastEventTime
        holder.typingView.setTextOrHide(typingMessage)
        holder.subtitleView.isInvisible = holder.typingView.isVisible
    }

    private fun renderForFilteredDisplayMode(holder: Holder) {
        holder.subtitleView.text = subtitle
    }

    override fun unbind(holder: Holder) {
        holder.rootView.setOnClickListener(null)
        holder.rootView.setOnLongClickListener(null)
        avatarRenderer.clear(holder.avatarImageView)
        super.unbind(holder)
    }

    private fun renderSelection(holder: Holder, isSelected: Boolean) {
        if (isSelected) {
            holder.avatarCheckedImageView.visibility = View.VISIBLE
            val backgroundColor = ThemeUtils.getColor(holder.view.context, com.google.android.material.R.attr.colorPrimary)
            val backgroundDrawable = TextDrawable.builder().buildRound("", backgroundColor)
            holder.avatarImageView.setImageDrawable(backgroundDrawable)
        } else {
            holder.avatarCheckedImageView.visibility = View.GONE
            avatarRenderer.render(matrixItem, holder.avatarImageView)
        }
    }

    private fun hasMicPermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    }

    class Holder : VectorEpoxyHolder() {
        val titleView by bind<TextView>(R.id.roomNameView)
        val unreadCounterBadgeView by bind<UnreadCounterBadgeView>(R.id.roomUnreadCounterBadgeView)
        val unreadIndentIndicator by bind<View>(R.id.roomUnreadIndicator)
        val subtitleView by bind<TextView>(R.id.subtitleView)
        val typingView by bind<TextView>(R.id.roomTypingView)
        val draftView by bind<ImageView>(R.id.roomDraftBadge)
        val lastEventTimeView by bind<TextView>(R.id.roomLastEventTimeView)
        val avatarCheckedImageView by bind<ImageView>(R.id.roomAvatarCheckedImageView)
        val avatarImageView by bind<ImageView>(R.id.roomAvatarImageView)
        val roomAvatarDecorationImageView by bind<ShieldImageView>(R.id.roomAvatarDecorationImageView)
        val roomAvatarPublicDecorationImageView by bind<ImageView>(R.id.roomAvatarPublicDecorationImageView)
        val roomAvatarFailSendingImageView by bind<ImageView>(R.id.roomAvatarFailSendingImageView)
        val roomAvatarPresenceImageView by bind<PresenceStateImageView>(R.id.roomAvatarPresenceImageView)
        val rootView by bind<ConstraintLayout>(R.id.itemRoomLayout)
    }
}
