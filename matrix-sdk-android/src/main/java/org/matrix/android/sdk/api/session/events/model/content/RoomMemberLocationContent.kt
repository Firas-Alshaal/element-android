/*
 * Copyright 2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.api.session.events.model.content

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/**
 * Class representing the content of a room member location state event.
 * This stores the last known location of a room member.
 */
@JsonClass(generateAdapter = true)
data class RoomMemberLocationContent(
        /**
         * Geographic location as URI (geo:lat,lon format).
         */
        @Json(name = "uri") val uri: String? = null,

        /**
         * Location data as a simple object.
         */
        @Json(name = "location") val location: LocationInfo? = null,

        /**
         * Timestamp when this location was recorded.
         */
        @Json(name = "ts") val timestamp: Long? = null
) {
    @JsonClass(generateAdapter = true)
    data class LocationInfo(
            @Json(name = "lat") val latitude: String,
            @Json(name = "lon") val longitude: String,
            @Json(name = "accuracy") val uncertainty: String? = null
    )
} 