/*
 * Copyright 2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.roomprofile.groupmap

import android.content.Context
import android.content.Intent
import android.os.Bundle
import dagger.hilt.android.AndroidEntryPoint
import im.vector.app.R
import im.vector.app.core.platform.VectorBaseActivity
import im.vector.app.core.extensions.addFragment
import im.vector.app.databinding.ActivityGroupMapBinding
import im.vector.app.features.roomprofile.RoomProfileArgs
import im.vector.lib.strings.CommonStrings

@AndroidEntryPoint
class GroupMapActivity : VectorBaseActivity<ActivityGroupMapBinding>() {

    override fun getBinding() = ActivityGroupMapBinding.inflate(layoutInflater)

    override fun initUiAndData() {
        val roomId = intent?.extras?.getString(EXTRA_ROOM_ID)
        if (roomId == null) {
            finish()
            return
        }

        setupToolbar(views.toolbar)
                .setTitle("Group Members Map")
                .allowBack()

        if (isFirstCreation()) {
            addFragment(
                    views.fragmentContainer,
                    GroupMapFragment::class.java,
                    RoomProfileArgs(roomId = roomId)
            )
        }
    }

    companion object {
        private const val EXTRA_ROOM_ID = "EXTRA_ROOM_ID"

        fun getIntent(context: Context, roomId: String): Intent {
            return Intent(context, GroupMapActivity::class.java).apply {
                putExtra(EXTRA_ROOM_ID, roomId)
            }
        }
    }
}
