/*
 * Copyright 2019-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.room.detail.composer

import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import org.matrix.android.sdk.api.query.QueryStringValue
import org.matrix.android.sdk.api.session.room.Room
import timber.log.Timber
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * 🏗️ مدير التنسيق المركزي للـ PTT
 * 
 * يدير بشكل موثوق:
 * - نشر وتحديث IP addresses
 * - إرسال PTT status events
 * - Retry logic ذكي
 * - State validation
 * - Error recovery
 * - Performance optimization
 * - Rate limiting protection
 */
object PttCoordinator {
    
    private const val MAX_RETRIES = 3
    private const val RETRY_DELAY_MS = 500L
    private const val IP_VALIDATION_TIMEOUT_MS = 2000L
    
    // 🛡️ Rate Limiting Protection
    private const val MIN_IP_UPDATE_INTERVAL_MS = 10000L // 10 ثواني
    private const val MIN_STATUS_UPDATE_INTERVAL_MS = 1000L // ثانية واحدة
    
    // 📊 Performance Caching
    private val lastIpUpdateTime = mutableMapOf<String, Long>()
    private val lastStatusUpdateTime = mutableMapOf<String, Long>()
    private val cachedLocalIp = mutableMapOf<String, Pair<String, Long>>() // IP, timestamp
    
    /**
     * 🔐 ضمان توفر IP للمستخدم مع validation ذكي
     */
    suspend fun ensureIpAvailable(room: Room, userId: String, localIp: String): Boolean {
        Timber.d("🔍 Ensuring IP availability for user: $userId")
        
        if (localIp == "unknown") {
            Timber.e("❌ Local IP is unknown - cannot proceed")
            return false
        }
        
        // 1. 🛡️ Rate Limiting Check
        val userKey = "${room.roomId}:$userId"
        val lastUpdate = lastIpUpdateTime[userKey] ?: 0L
        val timeSinceLastUpdate = System.currentTimeMillis() - lastUpdate
        
        if (timeSinceLastUpdate < MIN_IP_UPDATE_INTERVAL_MS) {
            Timber.d("🛡️ Rate limiting: IP update too frequent, skipping (${timeSinceLastUpdate}ms ago)")
            return true // اعتبار النجاح لتجنب إيقاف PTT
        }
        
        // 2. التحقق من IP الحالي
        val currentIp = getCurrentUserIp(room, userId)
        if (currentIp == localIp) {
            Timber.d("✅ IP already up-to-date: $localIp")
            return true
        }
        
        // 3. نشر IP جديد مع retry logic
        val success = publishIpWithRetry(room, userId, localIp)
        if (success) {
            lastIpUpdateTime[userKey] = System.currentTimeMillis()
        }
        
        return success
    }
    
    /**
     * 📡 إرسال PTT status مع تأكيد الوصول
     */
    suspend fun sendPttStatus(room: Room, userId: String, status: String): Boolean {
        Timber.d("📡 Sending PTT status: $status for user: $userId")
        
        // 🛡️ Rate Limiting Check for Status Updates
        val userKey = "${room.roomId}:$userId"
        val lastUpdate = lastStatusUpdateTime[userKey] ?: 0L
        val timeSinceLastUpdate = System.currentTimeMillis() - lastUpdate
        
        if (timeSinceLastUpdate < MIN_STATUS_UPDATE_INTERVAL_MS && status == "talking") {
            Timber.d("🛡️ Rate limiting: Status update too frequent, skipping")
            return true
        }
        
        return withRetry("Send PTT Status") {
            val content = mapOf(
                "status" to status,
                "userId" to userId,
                "timestamp" to System.currentTimeMillis()
            )
            
            room.stateService().sendStateEvent("ptt.status", userId, content)
            lastStatusUpdateTime[userKey] = System.currentTimeMillis()
            Timber.d("✅ PTT status '$status' sent successfully")
            true
        }
    }
    
    /**
     * 🔍 الحصول على IP الحالي للمستخدم مع Security Validation
     */
    private suspend fun getCurrentUserIp(room: Room, userId: String): String? {
        return try {
            val event = room.stateService().getStateEvent(
                eventType = "im.ptt.ip",
                stateKey = QueryStringValue.Equals(userId)
            )
            
            val ip = event?.content?.get("ip") as? String
            val timestamp = event?.content?.get("timestamp") as? Number
            
            // 🔒 Security Validations
            if (ip != null) {
                // 1. تحقق من صحة IP format
                if (!isValidIpAddress(ip)) {
                    Timber.w("🚫 Invalid IP format for user $userId: $ip")
                    return null
                }
                
                // 2. تحقق من أن IP محلي (أمان إضافي)
                if (!isPrivateNetworkIp(ip)) {
                    Timber.w("🚫 Non-private IP detected for user $userId: $ip")
                    return null
                }
                
                // 3. تحقق من عمر البيانات (منع replay attacks)
                timestamp?.let { ts ->
                    val age = System.currentTimeMillis() - ts.toLong()
                    if (age > 300000L) { // 5 دقائق
                        Timber.w("🚫 Stale IP data for user $userId (${age / 1000}s old)")
                        return null
                    }
                }
            }
            
            ip
        } catch (e: Exception) {
            Timber.w(e, "⚠️ Could not get current IP for user: $userId")
            null
        }
    }
    
    /**
     * 🔒 التحقق من صحة IP address format
     */
    private fun isValidIpAddress(ip: String): Boolean {
        return try {
            val parts = ip.split(".")
            if (parts.size != 4) return false
            
            parts.all { part ->
                val num = part.toIntOrNull()
                num != null && num in 0..255
            }
        } catch (e: Exception) {
            false
        }
    }
    
    /**
     * 🔒 التحقق من أن IP في شبكة محلية خاصة
     */
    private fun isPrivateNetworkIp(ip: String): Boolean {
        return ip.startsWith("10.") ||
               ip.startsWith("192.168.") ||
               (ip.startsWith("172.") && isPrivateNetwork172(ip)) ||
               ip.startsWith("127.") // localhost للاختبار
    }
    
    /**
     * 🔄 نشر IP مع retry logic متقدم
     */
    private suspend fun publishIpWithRetry(room: Room, userId: String, ip: String): Boolean {
        return withRetry("Publish IP") {
            // إرسال IP event
            val ipContent = mapOf(
                "ip" to ip,
                "timestamp" to System.currentTimeMillis(),
                "version" to "2.0"
            )
            
            room.stateService().sendStateEvent("im.ptt.ip", userId, ipContent)
            Timber.d("📤 IP event sent: $ip")
            
            // التحقق من وصول IP بنجاح
            val verified = verifyIpPublished(room, userId, ip)
            if (verified) {
                Timber.d("✅ IP successfully published and verified: $ip")
                true
            } else {
                Timber.w("⚠️ IP published but verification failed")
                false
            }
        }
    }
    
    /**
     * ✅ التحقق من نشر IP بنجاح
     */
    private suspend fun verifyIpPublished(room: Room, userId: String, expectedIp: String): Boolean {
        return withTimeoutOrNull(IP_VALIDATION_TIMEOUT_MS) {
            var attempts = 0
            while (attempts < 5) {
                delay(200L * (attempts + 1)) // تأخير متدرج
                
                val actualIp = getCurrentUserIp(room, userId)
                if (actualIp == expectedIp) {
                    return@withTimeoutOrNull true
                }
                
                attempts++
                Timber.d("🔄 IP verification attempt $attempts: expected=$expectedIp, actual=$actualIp")
            }
            false
        } ?: false
    }
    
    /**
     * 🔄 تنفيذ العمليات مع retry logic ذكي
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
                    Timber.e(e, "💥 $operationName failed after $maxRetries attempts")
                    throw e
                } else {
                    val delay = RETRY_DELAY_MS * (attempt + 1)
                    Timber.w(e, "⚠️ $operationName attempt ${attempt + 1} failed, retrying in ${delay}ms...")
                    delay(delay)
                }
            }
        }
        throw RuntimeException("This should never be reached")
    }
    
    /**
     * 🌐 الحصول على IP المحلي بشكل موثوق مع Caching
     */
    fun getLocalIpAddress(): String {
        val cacheKey = "local_ip"
        val now = System.currentTimeMillis()
        
        // 📊 التحقق من Cache (صالح لمدة دقيقة)
        cachedLocalIp[cacheKey]?.let { (cachedIp, timestamp) ->
            if (now - timestamp < 60000L && cachedIp != "unknown") {
                return cachedIp
            }
        }
        
        // 🔍 البحث عن IP جديد
        val ip = try {
            var bestIp = "unknown"
            val interfaces = NetworkInterface.getNetworkInterfaces()
            
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                
                // تجاهل interfaces غير نشطة
                if (!iface.isUp || iface.isLoopback) continue
                
                val addresses = iface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        val currentIp = addr.hostAddress ?: continue
                        
                        // إعطاء أولوية للشبكات المحلية
                        when {
                            currentIp.startsWith("10.") -> {
                                bestIp = currentIp
                                break // أفضل اختيار - توقف فوراً
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
            Timber.e(e, "❌ Failed to get local IP address")
            "unknown"
        }
        
        // 💾 Cache النتيجة
        cachedLocalIp[cacheKey] = ip to now
        Timber.d("🌐 Local IP detected: $ip")
        
        return ip
    }
    
    /**
     * 🔍 التحقق من شبكة 172.x الخاصة
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