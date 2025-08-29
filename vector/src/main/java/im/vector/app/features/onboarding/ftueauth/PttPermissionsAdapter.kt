/*
 * Copyright 2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.onboarding.ftueauth

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import im.vector.app.databinding.ItemPttPermissionBinding

class PttPermissionsAdapter(
    private var permissions: MutableList<FtueAuthPttPermissionsFragment.PttPermissionItem>,
    private val onPermissionClick: (String) -> Unit
) : RecyclerView.Adapter<PttPermissionsAdapter.PermissionViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PermissionViewHolder {
        val binding = ItemPttPermissionBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return PermissionViewHolder(binding)
    }

    override fun onBindViewHolder(holder: PermissionViewHolder, position: Int) {
        holder.bind(permissions[position])
    }

    override fun getItemCount(): Int = permissions.size

    fun updatePermissions(newPermissions: List<FtueAuthPttPermissionsFragment.PttPermissionItem>) {
        permissions.clear()
        permissions.addAll(newPermissions)
        notifyDataSetChanged()
    }

    inner class PermissionViewHolder(
        private val binding: ItemPttPermissionBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(permission: FtueAuthPttPermissionsFragment.PttPermissionItem) {
            with(binding) {
                // Set permission details
                permissionIcon.setImageResource(permission.icon)
                permissionTitle.text = permission.title
                permissionDescription.text = permission.description

                // Update status icons
                permissionGrantedIcon.isVisible = permission.isGranted
                permissionPendingIcon.isVisible = !permission.isGranted
                requiredBadge.isVisible = permission.isRequired && !permission.isGranted

                // Set click listener for non-granted permissions
                root.setOnClickListener {
                    if (!permission.isGranted) {
                        onPermissionClick(permission.permission)
                    }
                }

                // Update item appearance based on status
                root.alpha = if (permission.isGranted) 0.7f else 1.0f
                root.isClickable = !permission.isGranted
                root.isFocusable = !permission.isGranted
            }
        }
    }
}
