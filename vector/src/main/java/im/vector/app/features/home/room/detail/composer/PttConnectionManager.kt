/*
 * Copyright 2019-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.room.detail.composer

import kotlinx.coroutines.delay
import org.matrix.android.sdk.api.query.QueryStringValue
import org.matrix.android.sdk.api.session.room.Room
import timber.log.Timber
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Central PTT coordination manager
 * 
 * Manages reliably:
 * - Publishing and updating IP addresses
 * - Sending PTT status events
 * - Smart retry logic
 * - State validation
 * - Error recovery
 * - Performance optimization
 */
object PttCoordinator {
    
    private const val MAX_RETRIES = 3
    private const val RETRY_DELAY_MS = 500L
    
    /**
     * Ensure IP is available for user with smart validation
     */
    suspend fun ensureIpAvailable(room: Room, userId: String, localIp: String): Boolean {
        Timber.d("Ensuring IP availability for user: $userId")
        
        if (localIp == "unknown") {
            Timber.e("Local IP is unknown - cannot proceed")
            return false
        }
        
        // Check current IP
        val currentIp = getCurrentUserIp(room, userId)
        if (currentIp == localIp) {
            Timber.d("IP already up-to-date: $localIp")
            return true
        }
        
        // Publish new IP with retry logic
        return publishIpWithRetry(room, userId, localIp)
    }
    
    /**
     * Send PTT status with delivery confirmation
     */
    suspend fun sendPttStatus(room: Room, userId: String, status: String): Boolean {
        Timber.d("Sending PTT status: $status for user: $userId")
        
        return withRetry("Send PTT Status") {
            val content = mapOf(
                "status" to status,
                "userId" to userId,
                "timestamp" to System.currentTimeMillis()
            )
            
            room.stateService().sendStateEvent("ptt.status", userId, content)
            Timber.d("PTT status '$status' sent successfully")
            true
        }
    }
    
    /**
     * Get current IP for user
     */
    private suspend fun getCurrentUserIp(room: Room, userId: String): String? {
        return try {
            val event = room.stateService().getStateEvent(
                eventType = "im.ptt.ip",
                stateKey = QueryStringValue.Equals(userId)
            )
            
            event?.content?.get("ip") as? String
        } catch (e: Exception) {
            Timber.w(e, "Could not get current IP for user: $userId")
            null
        }
    }
    

    
    /**
     * Publish IP with retry logic
     */
    private suspend fun publishIpWithRetry(room: Room, userId: String, ip: String): Boolean {
        return withRetry("Publish IP") {
            val ipContent = mapOf(
                "ip" to ip,
                "timestamp" to System.currentTimeMillis()
            )
            
            room.stateService().sendStateEvent("im.ptt.ip", userId, ipContent)
            Timber.d("IP event sent: $ip")
            true
        }
    }
    

    
    /**
     * Execute operations with smart retry logic
     */
    private suspend fun <T> withRetry(
        operationName: String,
        maxRetries: Int = MAX_RETRIES,
        operation: suspend () -> T
    ): T {
        repeat(maxRetries) { attempt ->
            try {
                return operation()
            } catch (e: Exception) {
                val isLastAttempt = attempt == maxRetries - 1
                if (isLastAttempt) {
                    Timber.e(e, "$operationName failed after $maxRetries attempts")
                    throw e
                } else {
                    val delay = RETRY_DELAY_MS * (attempt + 1)
                    Timber.w(e, "$operationName attempt ${attempt + 1} failed, retrying in ${delay}ms...")
                    delay(delay)
                }
            }
        }
        throw RuntimeException("This should never be reached")
    }
    
    /**
     * Get local IP address reliably
     */
    fun getLocalIpAddress(): String {
        return try {
            var bestIp = "unknown"
            val interfaces = NetworkInterface.getNetworkInterfaces()
            
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                
                // Ignore inactive interfaces
                if (!iface.isUp || iface.isLoopback) continue
                
                val addresses = iface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        val currentIp = addr.hostAddress ?: continue
                        
                        // Prioritize local networks
                        when {
                            currentIp.startsWith("10.") -> {
                                bestIp = currentIp
                                break // Best choice - stop immediately
                            }
                            currentIp.startsWith("192.168.") -> bestIp = currentIp
                            currentIp.startsWith("172.") && isPrivateNetwork172(currentIp) -> {
                                if (bestIp == "unknown") bestIp = currentIp
                            }
                        }
                    }
                }
                if (bestIp.startsWith("10.")) break // found best option
            }
            bestIp
        } catch (e: Exception) {
            Timber.e(e, "Failed to get local IP address")
            "unknown"
        }.also { ip ->
            Timber.d("Local IP detected: $ip")
        }
    }
    
    /**
     * Check if IP is in private 172.x network
     */
    private fun isPrivateNetwork172(ip: String): Boolean {
        val parts = ip.split(".")
        if (parts.size != 4) return false
        
        return try {
            val second = parts[1].toInt()
            second in 16..31 // 172.16.0.0 to 172.31.255.255
        } catch (e: NumberFormatException) {
            false
        }
    }
}
