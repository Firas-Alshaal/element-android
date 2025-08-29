/*
 * Copyright 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.core.login

import android.content.Context
import org.matrix.android.sdk.api.session.Session

interface PostLoginAction {
    suspend fun onUserLoggedIn(context: Context, session: Session)
}
