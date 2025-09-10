/*
 * Copyright 2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.callhistory

import android.content.Context
import android.content.Intent
import android.os.Bundle
import dagger.hilt.android.AndroidEntryPoint
import im.vector.app.R
import im.vector.app.core.platform.VectorBaseActivity
import im.vector.app.databinding.ActivityCallHistoryBinding

@AndroidEntryPoint
class CallHistoryActivity : VectorBaseActivity<ActivityCallHistoryBinding>() {

    companion object {
        fun newIntent(context: Context): Intent {
            return Intent(context, CallHistoryActivity::class.java)
        }
    }

    override fun getBinding() = ActivityCallHistoryBinding.inflate(layoutInflater)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupToolbar()
        supportFragmentManager.beginTransaction()
            .add(R.id.callHistoryContainer, CallHistoryFragment())
            .commit()
    }

    private fun setupToolbar() {
        setSupportActionBar(views.callHistoryToolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setDisplayShowHomeEnabled(true)
        supportActionBar?.title = "Call History"
        views.callHistoryToolbar.setNavigationOnClickListener {
            finish()
        }
    }

}
