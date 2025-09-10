/*
 * Copyright 2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.callhistory

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import im.vector.app.R
import im.vector.app.databinding.ItemCallHistoryBinding
import im.vector.app.features.home.AvatarRenderer
import org.matrix.android.sdk.api.util.MatrixItem
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

class CallHistoryAdapter(
    private val avatarRenderer: AvatarRenderer
) : RecyclerView.Adapter<CallHistoryAdapter.CallHistoryViewHolder>() {

    private var callHistory: List<CallHistoryItem> = emptyList()

    fun updateCallHistory(newCallHistory: List<CallHistoryItem>) {
        callHistory = newCallHistory
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CallHistoryViewHolder {
        val binding = ItemCallHistoryBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return CallHistoryViewHolder(binding)
    }

    override fun onBindViewHolder(holder: CallHistoryViewHolder, position: Int) {
        holder.bind(callHistory[position])
    }

    override fun getItemCount() = callHistory.size

    inner class CallHistoryViewHolder(
        private val binding: ItemCallHistoryBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: CallHistoryItem) {
            // Room name
            binding.roomNameText.text = item.roomName

            // Call direction and type
            setupCallInfo(item)

            // Timestamp
            binding.timestampText.text = formatTimestamp(item.timestamp)

            // Duration or status
            binding.durationText.text = if (item.callDirection == CallDirection.MISSED) {
                "Missed"
            } else {
                formatDuration(item.duration)
            }

            // Avatar
            val matrixItem = MatrixItem.RoomItem(
                id = item.roomId,
                displayName = item.roomName,
                avatarUrl = item.avatarUrl
            )
            avatarRenderer.render(matrixItem, binding.avatarImageView)

            // Group indicator
            binding.groupIndicator.visibility = if (item.isGroup) {
                android.view.View.VISIBLE
            } else {
                android.view.View.GONE
            }
        }

        private fun setupCallInfo(item: CallHistoryItem) {
            val (icon, tint) = when {
                item.callDirection == CallDirection.MISSED -> {
                    val iconRes = if (item.callType == CallType.VIDEO) R.drawable.ic_missed_video_call else R.drawable.ic_missed_voice_call
                    Pair(iconRes, android.R.color.holo_red_dark)
                }
                item.callDirection == CallDirection.OUTGOING -> {
                    val iconRes = if (item.callType == CallType.VIDEO) R.drawable.ic_video else R.drawable.ic_call
                    Pair(iconRes, android.R.color.holo_green_dark)
                }
                else -> { // INCOMING
                    val iconRes = if (item.callType == CallType.VIDEO) R.drawable.ic_call_answer_video else R.drawable.ic_call_answer
                    Pair(iconRes, android.R.color.holo_green_dark)
                }
            }

            binding.callTypeIcon.setImageResource(icon)
            binding.callTypeIcon.setColorFilter(
                binding.root.context.getColor(tint)
            )

            binding.callDirectionText.text = when (item.callDirection) {
                CallDirection.OUTGOING -> "Outgoing ${if (item.callType == CallType.VIDEO) "video" else "voice"} call"
                CallDirection.INCOMING -> "Incoming ${if (item.callType == CallType.VIDEO) "video" else "voice"} call"
                CallDirection.MISSED -> "Missed ${if (item.callType == CallType.VIDEO) "video" else "voice"} call"
            }
        }

        private fun formatTimestamp(timestamp: Long): String {
            val now = System.currentTimeMillis()
            val diff = now - timestamp

            return when {
                diff < TimeUnit.MINUTES.toMillis(2) -> "Just now"
                diff < TimeUnit.MINUTES.toMillis(60) -> {
                    val minutes = TimeUnit.MILLISECONDS.toMinutes(diff)
                    "${minutes}m ago"
                }
                diff < TimeUnit.DAYS.toMillis(1) -> {
                    val formatter = SimpleDateFormat("HH:mm", Locale.getDefault())
                    formatter.format(Date(timestamp))
                }
                diff < TimeUnit.DAYS.toMillis(7) -> {
                    val formatter = SimpleDateFormat("EEE HH:mm", Locale.getDefault())
                    formatter.format(Date(timestamp))
                }
                else -> {
                    val formatter = SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault())
                    formatter.format(Date(timestamp))
                }
            }
        }

        private fun formatDuration(duration: Long): String {
            if (duration == 0L) {
                return "No answer"
            }
            
            val seconds = duration / 1000
            val minutes = seconds / 60
            val remainingSeconds = seconds % 60
            
            return when {
                minutes > 0 -> String.format("%d:%02d", minutes, remainingSeconds)
                seconds > 0 -> "${seconds}s"
                else -> "< 1s"
            }
        }
    }
}
