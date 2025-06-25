/*
 * Copyright 2018-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */
package im.vector.app.push.fcm


import android.content.Context
import com.google.firebase.firestore.FirebaseFirestore
import timber.log.Timber

/**
 * This class store the FCM token in SharedPrefs and ensure this token is retrieved.
 * It has an alter ego in the fdroid variant.
 */
object FirestoreHelper {

    fun saveUserPushToken(userId: String, pushToken: String) {
        val db = FirebaseFirestore.getInstance()
        val tokenData = mapOf("fcmToken" to pushToken)

        db.collection("user_push_tokens")
                .document(userId)
                .set(tokenData)
                .addOnSuccessListener {
                    Timber.d("✅ FCM token saved for $userId")
                }
                .addOnFailureListener { e ->
                    Timber.e(e, "❌ Failed to save FCM token")
                }
    }

    fun getPushToken(userId: String, callback: (String?) -> Unit) {
        val db = FirebaseFirestore.getInstance()

        db.collection("user_push_tokens")
                .document(userId)
                .get()
                .addOnSuccessListener { document ->
                    callback(document.getString("fcmToken"))
                }
                .addOnFailureListener { e ->
                    Timber.e(e, "❌ Failed to fetch FCM token for $userId")
                    callback(null)
                }
    }
}
