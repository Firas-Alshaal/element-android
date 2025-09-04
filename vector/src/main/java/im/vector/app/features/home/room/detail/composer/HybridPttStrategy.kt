/*
 * Copyright 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.room.detail.composer

import timber.log.Timber

object HybridPttStrategy {

    // يحدد نوع النقل المناسب حسب IP المستقبل
    fun determineTransport(localIp: String?, remoteIp: String?): PttTransportType {
        if (localIp == null || remoteIp == null) {
            Timber.d("🚫 HybridPttStrategy: null IP detected, using MATRIX")
            return PttTransportType.MATRIX
        }

        val sameSubnet = isOnSameSubnet(localIp, remoteIp)
        Timber.d("🔍 HybridPttStrategy: localIp=$localIp, remoteIp=$remoteIp, sameSubnet=$sameSubnet")

        return if (sameSubnet) {
            Timber.d("✅ HybridPttStrategy: Same subnet detected, using TCP")
            PttTransportType.TCP
        } else {
            Timber.d("🌐 HybridPttStrategy: Different subnets, using MATRIX")
            PttTransportType.MATRIX
        }
    }

    // مقارنة subnet للأجهزة (192.168.X أو 10.X أو 172.16.X)
    private fun isOnSameSubnet(ip1: String, ip2: String): Boolean {
        val prefix1 = ip1.substringBeforeLast(".")
        val prefix2 = ip2.substringBeforeLast(".")
        return prefix1 == prefix2
    }
}

enum class PttTransportType {
    TCP,
    MATRIX
}
