/*
 * Copyright 2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.callhistory

enum class CallType {
    VOICE,
    VIDEO
}

enum class CallDirection {
    INCOMING,
    OUTGOING,
    MISSED
}

data class CallHistoryItem(
    val callId: String,
    val roomName: String,
    val roomId: String,
    val callType: CallType,
    val callDirection: CallDirection,
    val timestamp: Long,
    val duration: Long, // in milliseconds
    val avatarUrl: String?,
    val isGroup: Boolean
)
