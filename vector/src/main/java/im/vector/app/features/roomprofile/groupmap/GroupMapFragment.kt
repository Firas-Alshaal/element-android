/*
 * Copyright 2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.roomprofile.groupmap

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.airbnb.mvrx.args
import com.airbnb.mvrx.fragmentViewModel
import com.airbnb.mvrx.withState
import com.esri.arcgisruntime.layers.ArcGISVectorTiledLayer
import com.esri.arcgisruntime.mapping.ArcGISMap
import com.esri.arcgisruntime.mapping.Basemap

import dagger.hilt.android.AndroidEntryPoint
import im.vector.app.R
import im.vector.app.core.extensions.cleanup
import im.vector.app.core.platform.VectorBaseFragment
import im.vector.app.databinding.FragmentGroupMapBinding
import im.vector.app.features.roomprofile.RoomProfileArgs
import im.vector.lib.strings.CommonStrings
import kotlinx.coroutines.launch
import timber.log.Timber
import com.mapbox.mapboxsdk.maps.MapView
import com.mapbox.mapboxsdk.maps.MapboxMap
import com.mapbox.mapboxsdk.maps.Style
import com.mapbox.mapboxsdk.geometry.LatLng
import javax.inject.Inject

@AndroidEntryPoint
class GroupMapFragment : VectorBaseFragment<FragmentGroupMapBinding>() {

    private val viewModel: GroupMapViewModel by fragmentViewModel()
    private val roomProfileArgs: RoomProfileArgs by args()

    @Inject lateinit var vtpkProvider: VtpkMapProvider

    override fun getBinding(inflater: LayoutInflater, container: ViewGroup?): FragmentGroupMapBinding {
        return FragmentGroupMapBinding.inflate(inflater, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        views.loadingView.isVisible = true
        views.errorView.isVisible = false
        views.esriMapView.visibility = View.GONE

        // انسخ ملفات VTPK من الأصول (مرة واحدة)
        vtpkProvider.copyVtpkFilesFromAssets()

        // اختر أي خريطة (UAE الافتراضية)
        val vtpkPath = vtpkProvider.getUaeMapPath() ?: run {
            showError("VTPK (UAE) غير موجود")
            return
        }

        try {
            val layer = ArcGISVectorTiledLayer(vtpkPath)
            val basemap = Basemap(layer)
            val map = ArcGISMap(basemap)
            views.esriMapView.map = map

            // إزاحة الكاميرا نحو الإمارات (اختياري)
            // ملاحظة: ممكن تستخدم Viewpoint حين يصير map load done
            map.addDoneLoadingListener {
                // Zoom تقريبي للإمارات
                views.esriMapView.setViewpointCenterAsync(
                        com.esri.arcgisruntime.geometry.Point(54.3773, 24.4539, com.esri.arcgisruntime.geometry.SpatialReferences.getWgs84()),
                        5_000_00.0 // مسافة (متر) – عدّل حسب الحاجة
                )
            }

            views.loadingView.isVisible = false
            views.errorView.isVisible = false
            views.esriMapView.visibility = View.VISIBLE

            viewModel.handle(GroupMapAction.LoadMap) // لو تبغى تبقيها للإشارات الحالية

        } catch (e: Exception) {
            Timber.e(e, "Failed to load VTPK")
            showError(e.message ?: "Unknown error")
        }
    }

    private fun showError(msg: String) {
        views.loadingView.isVisible = false
        views.errorView.isVisible = true
        views.errorText.text = msg
        views.esriMapView.visibility = View.GONE
        viewModel.handle(GroupMapAction.LoadMembers) // أو MapLoadError حسب حدثك
    }

    // دورة حياة ArcGIS MapView
    override fun onResume() { super.onResume(); views.esriMapView.resume() }
    override fun onPause() { views.esriMapView.pause(); super.onPause() }
    override fun onDestroyView() { views.esriMapView.dispose(); super.onDestroyView() }

    override fun invalidate() {
        withState(viewModel) { state ->
            views.loadingView.isVisible = state.isLoading
            views.errorView.isVisible = state.error != null
            state.error?.let { views.errorText.text = it }
        }
    }
}
