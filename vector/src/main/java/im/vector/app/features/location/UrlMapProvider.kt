/*
 * Copyright 2022-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.location

import im.vector.app.features.raw.wellknown.getElementWellknown
import org.matrix.android.sdk.api.extensions.tryOrNull
import org.matrix.android.sdk.api.raw.RawService
import org.matrix.android.sdk.api.session.Session
import javax.inject.Inject

class UrlMapProvider @Inject constructor(
        private val session: Session,
        private val rawService: RawService,
        locationSharingConfig: LocationSharingConfig,
        private val offlineMapProvider: OfflineMapProvider,
) {
    private val keyParam = "?key=${locationSharingConfig.mapTilerKey}"

    private val fallbackMapUrl = buildString {
        append(MAP_BASE_URL)
        append(keyParam)
    }

    suspend fun getMapUrl(): String {
        val upstreamMapUrl = tryOrNull { rawService.getElementWellknown(session.sessionParams) }
                ?.getBestMapTileServerConfig()
                ?.mapStyleUrl
        return upstreamMapUrl ?: fallbackMapUrl
    }

    /**
     * Returns offline map style for use without internet connection
     */
    fun getOfflineMapUrl(): String {
        return try {
            // Try the enhanced offline style first
            val styleJson = offlineMapProvider.getOfflineMapStyleJson()
            "data:application/json;charset=utf-8," + java.net.URLEncoder.encode(styleJson, "UTF-8")
        } catch (e: Exception) {
            // Fallback to simple style if enhanced fails
            try {
                val simpleStyleJson = offlineMapProvider.getSimpleOfflineMapStyleJson()
                "data:application/json;charset=utf-8," + java.net.URLEncoder.encode(simpleStyleJson, "UTF-8")
            } catch (fallbackException: Exception) {
                // Ultimate fallback - direct JSON without encoding issues
                "data:application/json;charset=utf-8," + """{"version":8,"name":"Basic Offline Map","sources":{},"layers":[{"id":"background","type":"background","paint":{"background-color":"#f0f8ff"}}]}"""
            }
        }
    }

    fun buildStaticMapUrl(
            locationData: LocationData,
            zoom: Double,
            width: Int,
            height: Int
    ): String {
        return buildString {
            append(STATIC_MAP_BASE_URL)
            append(locationData.longitude)
            append(",")
            append(locationData.latitude)
            append(",")
            append(zoom)
            append("/")
            append(width)
            append("x")
            append(height)
            append(".png")
            append(keyParam)
            // Since the default copyright font is too small we put a custom one on map
            append("&attribution=false")
        }
    }
}
