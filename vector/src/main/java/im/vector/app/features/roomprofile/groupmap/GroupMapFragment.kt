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
import com.airbnb.mvrx.args
import com.airbnb.mvrx.fragmentViewModel
import com.airbnb.mvrx.withState
import com.esri.arcgisruntime.layers.ArcGISVectorTiledLayer
import com.esri.arcgisruntime.mapping.ArcGISMap
import com.esri.arcgisruntime.mapping.Basemap
import com.esri.arcgisruntime.geometry.Envelope
import com.esri.arcgisruntime.geometry.Point
import com.esri.arcgisruntime.geometry.SpatialReferences
import com.esri.arcgisruntime.mapping.Viewpoint
import com.esri.arcgisruntime.data.TileCache
import com.esri.arcgisruntime.layers.ArcGISTiledLayer
import com.esri.arcgisruntime.loadable.LoadStatus
import com.esri.arcgisruntime.mapping.MobileMapPackage
import dagger.hilt.android.AndroidEntryPoint
import im.vector.app.core.platform.VectorBaseFragment
import im.vector.app.databinding.FragmentGroupMapBinding
import im.vector.app.features.roomprofile.RoomProfileArgs
import timber.log.Timber
import javax.inject.Inject
import java.io.File
import im.vector.app.R

@AndroidEntryPoint
class GroupMapFragment : VectorBaseFragment<FragmentGroupMapBinding>() {

    private val viewModel: GroupMapViewModel by fragmentViewModel()
    private val roomProfileArgs: RoomProfileArgs by args()

    @Inject lateinit var vtpkProvider: VtpkMapProvider

    // Map boundaries for UAE region (prevent zooming out too far)
    private val uaeBoundary = Envelope(
            Point(51.0, 22.0, SpatialReferences.getWgs84()),
            Point(57.0, 26.0, SpatialReferences.getWgs84())
    )

    override fun getBinding(inflater: LayoutInflater, container: ViewGroup?): FragmentGroupMapBinding {
        return FragmentGroupMapBinding.inflate(inflater, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupViews()
        setupMapControls()
        observeViewEvents()
        loadMap()
    }

    private fun setupViews() {
        views.loadingView.isVisible = true
        views.errorView.isVisible = false
        views.esriMapView.visibility = View.GONE
        views.mapControlPanel.visibility = View.GONE
    }

    private fun setupMapControls() {
        // Setup single style toggle button
        views.btnMapStyleToggle.setOnClickListener {
            toggleMapStyle()
        }

        // Setup ESRI zoom controls
        setupEsriZoomControls()

        // Set initial style (Standard is default)
        currentMapStyle = VtpkMapProvider.STYLE_STREETS
        updateStyleButtonIcon()
    }

    private var currentMapStyle = VtpkMapProvider.STYLE_STREETS
    private var isLoadingMap = false  // حماية من التحميل المتعدد

    private fun toggleMapStyle() {
        currentMapStyle = if (currentMapStyle == VtpkMapProvider.STYLE_STREETS) {
            VtpkMapProvider.STYLE_SATELLITE
        } else {
            VtpkMapProvider.STYLE_STREETS
        }

        Timber.d("Toggling map style to: $currentMapStyle")

        // Update button icon
        updateStyleButtonIcon()

        // Load new map style
        loadMapWithStyle(currentMapStyle)
    }

    private fun updateStyleButtonIcon() {
        val iconRes = if (currentMapStyle == VtpkMapProvider.STYLE_STREETS) {
            R.drawable.ic_map_standard
        } else {
            R.drawable.ic_map_satellite
        }
        views.btnMapStyleToggle.setImageResource(iconRes)
    }

    private fun setupEsriZoomControls() {
        try {
            Timber.d("🎮 Setting up custom zoom controls")

            // Setup custom zoom buttons with detailed logging
            views.zoomInButton.setOnClickListener {
                Timber.d("🔍 Zoom In button clicked")
                zoomIn()
            }
            views.zoomOutButton.setOnClickListener {
                Timber.d("🔍 Zoom Out button clicked")
                zoomOut()
            }

            Timber.d("✅ Custom zoom controls enabled successfully")
        } catch (e: Exception) {
            Timber.e(e, "❌ Failed to setup zoom controls")
        }
    }

    private fun zoomIn() {
        try {
            val mapView = views.esriMapView
            val map = mapView.map

            if (map == null) {
                Timber.w("⚠️ Map is null, cannot zoom in")
                return
            }

            val currentScale = mapView.mapScale
            Timber.d("🔍 Current scale before zoom in: $currentScale")

            // التحقق من الحد الأقصى للتكبير
            val maxScale = map.maxScale
            if (currentScale <= maxScale * 1.1) { // margin for floating point errors
                Timber.w("⚠️ Already at maximum zoom level: $currentScale (max: $maxScale)")
                return
            }

            val zoomFactor = 0.5 // تكبير بمعامل 2
            val newScale = (currentScale * zoomFactor).coerceAtLeast(maxScale)

            Timber.d("🔍 Zooming in: $currentScale → $newScale")

            mapView.setViewpointScaleAsync(newScale).addDoneListener {
                Timber.d("✅ Zoom in completed successfully")
            }
        } catch (e: Exception) {
            Timber.e(e, "❌ Failed to zoom in")
        }
    }

    private fun zoomOut() {
        try {
            val mapView = views.esriMapView
            val map = mapView.map

            if (map == null) {
                Timber.w("⚠️ Map is null, cannot zoom out")
                return
            }

            val currentScale = mapView.mapScale
            Timber.d("🔍 Current scale before zoom out: $currentScale")

            // التحقق من الحد الأدنى للتصغير
            val minScale = map.minScale
            if (currentScale >= minScale * 0.9) { // margin for floating point errors
                Timber.w("⚠️ Already at minimum zoom level: $currentScale (min: $minScale)")
                return
            }

            val zoomFactor = 2.0 // تصغير بمعامل 2
            val newScale = (currentScale * zoomFactor).coerceAtMost(minScale)

            Timber.d("🔍 Zooming out: $currentScale → $newScale")

            mapView.setViewpointScaleAsync(newScale).addDoneListener {
                Timber.d("✅ Zoom out completed successfully")
            }
        } catch (e: Exception) {
            Timber.e(e, "❌ Failed to zoom out")
        }
    }

    private fun loadMap() {
        try {
            views.loadingView.isVisible = true
            views.errorView.isVisible = false

            // انسخ ملفات VTPK من الأصول (مرة واحدة)
            val vtpkProvider = viewModel.getVtpkProvider()
            Timber.d("Starting VTPK file copy process...")
            vtpkProvider.copyVtpkFilesFromAssets()

            // تحقق من توفر الملفات
            if (!vtpkProvider.areVtpkFilesAvailable()) {
                Timber.e("VTPK files are not available after copy attempt")
                showError("فشل في تحميل ملفات الخريطة. يرجى المحاولة مرة أخرى.")
                return
            }

            // اختر أي خريطة (Streets الافتراضية)
            val vtpkPath = vtpkProvider.getStreetsMapPath()
            if (vtpkPath == null) {
                Timber.e("Streets map path is null")
                showError("مسار خريطة الشوارع غير متوفر")
                return
            }

            Timber.d("Loading map from: $vtpkPath")
            val file = File(vtpkPath)
            if (!file.exists()) {
                Timber.e("VTPK file does not exist: $vtpkPath")
                showError("ملف الخريطة غير موجود: ${file.name}")
                return
            }

            val layer = ArcGISVectorTiledLayer(vtpkPath)
            val basemap = Basemap(layer)
            val map = ArcGISMap(basemap)

            // Set map constraints to prevent zooming out too far
            map.minScale = 1000000.0 // 1:1M scale (zoom out limit)
            map.maxScale = 1000.0    // 1:1K scale (zoom in limit)

            views.esriMapView.map = map

            // إزاحة الكاميرا نحو الإمارات (اختياري)
            // ملاحظة: ممكن تستخدم Viewpoint حين يصير map load done
            map.addDoneLoadingListener {
                // Zoom تقريبي للإمارات
                val uaeCenter = Point(54.3773, 24.4539, SpatialReferences.getWgs84())
                val viewpoint = Viewpoint(uaeCenter, 500000.0) // 500km zoom
                views.esriMapView.setViewpointAsync(viewpoint)

                // Show controls after map is loaded
                showMapControls()
            }

            views.loadingView.isVisible = false
            views.errorView.isVisible = false
            views.esriMapView.visibility = View.VISIBLE

            // Notify ViewModel that map is loaded
            viewModel.handle(GroupMapAction.LoadMap)
        } catch (e: Exception) {
            Timber.e(e, "Failed to load VTPK")
            showError("خطأ في تحميل الخريطة: ${e.message}")
        }
    }

    private fun loadMapWithStyle(style: String) {
        try {
            // 🚫 حماية من التحميل المتعدد
            if (isLoadingMap) {
                Timber.w("⚠️ Map is already loading, ignoring request for style: $style")
                return
            }
            
            isLoadingMap = true
            Timber.d("🗺️ Switching to map style: $style")

            // 🧼 إيقاف أي عمليات تحميل سابقة وتنظيف الخريطة
            views.esriMapView.map?.let { currentMap ->
                currentMap.operationalLayers.clear()
                currentMap.basemap.baseLayers.clear()
                currentMap.basemap.referenceLayers.clear()
            }
            
            views.esriMapView.map = null
            views.loadingView.isVisible = true
            views.esriMapView.visibility = View.VISIBLE // تأكد من بقاء MapView مرئية
            
            // إضافة تأخير قصير للتأكد من تنظيف الحالة السابقة
            views.esriMapView.post {
                loadMapWithStyleInternal(style)
            }
        } catch (e: Exception) {
            Timber.e(e, "❌ loadMapWithStyle failed")
            isLoadingMap = false  // إعادة تعيين في حالة الخطأ
            showError("خطأ غير متوقع: ${e.message}")
        }
    }
    
    private fun loadMapWithStyleInternal(style: String) {
        try {
            val vtpkProvider = this.vtpkProvider

            if (!vtpkProvider.areVtpkFilesAvailable()) {
                showError("الملفات غير متوفرة")
                return
            }

            vtpkProvider.setCurrentStyle(style)
            val mapPath = vtpkProvider.getMapPathForStyle(style)

            if (mapPath.isNullOrEmpty()) {
                showError("لم يتم العثور على المسار للستايل: $style")
                return
            }

            when {
                mapPath.endsWith(".mmpk") -> {
                    // 📦 تحميل حزمة خريطة محمولة MMPK
                    val mobileMapPackage = MobileMapPackage(mapPath)
                    mobileMapPackage.loadAsync()
                    mobileMapPackage.addDoneLoadingListener {
                        if (mobileMapPackage.loadStatus == LoadStatus.LOADED) {
                            val mmpkMap = mobileMapPackage.maps.first()
                            
                            // تعيين الخريطة بشكل مستقر
                            views.esriMapView.post {
                                views.esriMapView.map = mmpkMap
                                
                                mmpkMap.addDoneLoadingListener {
                                    views.esriMapView.post {
                                        centerMapOnUae()
                                        views.loadingView.isVisible = false
                                        views.esriMapView.visibility = View.VISIBLE
                                        
                                        // إجبار إعادة رسم
                                        views.esriMapView.requestLayout()
                                        views.esriMapView.invalidate()
                                        
                                        Timber.d("✅ MMPK map loaded and displayed successfully")
                                        isLoadingMap = false  // انتهاء التحميل
                                    }
                                }
                            }
                        } else {
                            isLoadingMap = false  // إعادة تعيين في حالة الخطأ
                            showError("فشل تحميل حزمة MMPK: ${mobileMapPackage.loadError?.message}")
                        }
                    }
                }

                mapPath.endsWith(".tpkx") -> {
                    // 🛰️ تحميل صور فضائية ودمجها مع طبقات مسميات من MMPK
                    Timber.d("🛰️ Loading satellite imagery from TPKX: $mapPath")
                    
                    // فحص حجم ملف الستلايت
                    val satelliteFile = java.io.File(mapPath)
                    val fileSizeMB = satelliteFile.length() / (1024 * 1024)
                    Timber.d("🛰️ Satellite file size: ${fileSizeMB}MB, exists: ${satelliteFile.exists()}")
                    
                    val tileCache = TileCache(mapPath)
                    val imageryLayer = ArcGISTiledLayer(tileCache)
                    
                    // فحص صحة ملف الستلايت
                    imageryLayer.addDoneLoadingListener {
                        val loadStatus = imageryLayer.loadStatus
                        Timber.d("🛰️ Satellite imagery layer load status: $loadStatus")
                        
                        if (loadStatus == LoadStatus.LOADED) {
                            Timber.d("🛰️ Satellite layer loaded successfully - visible: ${imageryLayer.isVisible}, opacity: ${imageryLayer.opacity}")
                            // التأكد من إعدادات الرؤية
                            imageryLayer.isVisible = true
                            imageryLayer.opacity = 1.0f
                        } else {
                            val error = imageryLayer.loadError
                            Timber.e("❌ Satellite imagery failed to load: ${error?.message}")
                        }
                    }
                    
                    val mmpkPath = vtpkProvider.getMmpkMapPath()
                    Timber.d("📦 MMPK path for labels: $mmpkPath")
                    
                    // فحص حجم ملف MMPK
                    if (!mmpkPath.isNullOrEmpty()) {
                        val mmpkFile = java.io.File(mmpkPath)
                        val mmpkSizeMB = mmpkFile.length() / (1024 * 1024)
                        Timber.d("📦 MMPK file size: ${mmpkSizeMB}MB, exists: ${mmpkFile.exists()}")
                        
                        if (!mmpkFile.exists()) {
                            Timber.e("❌ MMPK file does not exist!")
                        } else if (mmpkFile.length() < 1024) {  // أقل من 1KB
                            Timber.e("❌ MMPK file is too small (${mmpkFile.length()} bytes) - likely corrupt")
                        }
                    }
                    
                    // ✅ تم اكتشاف أن الستلايت يحمل بنجاح - الآن ننتقل للتسميات
                    imageryLayer.loadAsync()
                    
                    // تحميل الستلايت مع تسميات VTPK (الحل الموثوق)
                    Timber.d("🛰️ Loading satellite with VTPK labels (MMPK is empty)")
                    loadSatelliteWithVtpkLabels(tileCache)
                }

                mapPath.endsWith(".vtpk") -> {
                    val vectorLayer = ArcGISVectorTiledLayer(mapPath)
                    val basemap = Basemap(vectorLayer)
                    val map = ArcGISMap(basemap)

                    // تعيين الخريطة بشكل مستقر
                    views.esriMapView.post {
                        views.esriMapView.map = map
                        
                        map.addDoneLoadingListener {
                            views.esriMapView.post {
                                centerMapOnUae()
                                views.loadingView.isVisible = false
                                views.esriMapView.visibility = View.VISIBLE
                                
                                // إجبار إعادة رسم
                                views.esriMapView.requestLayout()
                                views.esriMapView.invalidate()
                                
                                Timber.d("✅ Vector map loaded and displayed successfully")
                                isLoadingMap = false  // انتهاء التحميل
                            }
                        }
                    }
                }

                else -> {
                    showError("صيغة غير مدعومة: $mapPath")
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "❌ loadMapWithStyle failed")
            showError("خطأ غير متوقع: ${e.message}")
        }
    }

    private fun centerMapOnUae() {
        try {
            val uaeCenter = Point(54.3773, 24.4539, SpatialReferences.getWgs84())
            val desiredScale = 800000.0 // مقياس أكبر (بعيد أكثر) لإظهار منطقة أوسع

            Timber.d("🎯 Setting UAE center with scale: $desiredScale")

            // التأكد من أن الخريطة جاهزة قبل تعيين الموقع
            views.esriMapView.post {
                if (views.esriMapView.map != null) {
                    views.esriMapView.setViewpointAsync(Viewpoint(uaeCenter, desiredScale)).addDoneListener {
                        val actualScale = views.esriMapView.mapScale
                        Timber.d("🎯 UAE center set - Requested: $desiredScale, Actual: $actualScale")

                        // إذا كان المقياس الفعلي مختلف جداً، جرب مقياس أكبر
                        if (actualScale > desiredScale * 10) {
                            val saferScale = 2000000.0 // مقياس أكبر (أبعد)
                            Timber.w("⚠️ Scale too close, trying safer scale: $saferScale")
                            views.esriMapView.setViewpointAsync(Viewpoint(uaeCenter, saferScale))
                        }
                    }
                } else {
                    Timber.w("⚠️ Map is null when trying to center on UAE")
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "❌ Failed to center map on UAE")
        }
    }

    /**
     * 🛰️ تحميل صور الستلايت مع محاولة إضافة تسميات من VTPK
     */
    private fun loadSatelliteWithVtpkLabels(tileCache: TileCache) {
        try {
            Timber.d("🛰️ Loading satellite with VTPK labels fallback")
            
            val imageryLayer = ArcGISTiledLayer(tileCache)
            val vtpkPath = vtpkProvider.getHybridLabelsPath()
            
            if (!vtpkPath.isNullOrEmpty()) {
                Timber.d("🏷️ Trying to add VTPK labels from: $vtpkPath")
                
                val vectorLabelsLayer = ArcGISVectorTiledLayer(vtpkPath)
                vectorLabelsLayer.addDoneLoadingListener {
                    if (vectorLabelsLayer.loadStatus == LoadStatus.LOADED) {
                        Timber.d("✅ VTPK labels loaded successfully")
                        
                        // فحص محتوى طبقة VTPK
                        Timber.d("🔍 VTPK layer info: visible=${vectorLabelsLayer.isVisible}, opacity=${vectorLabelsLayer.opacity}")
                        
                        // تأكد من إعدادات الرؤية للطبقة VTPK - تقليل الشفافية قليلاً
                        vectorLabelsLayer.isVisible = true
                        vectorLabelsLayer.opacity = 0.8f  // تقليل قليلاً لضمان ظهور الستلايت
                        
                        // التأكد من إعدادات طبقة الستلايت أولاً
                        imageryLayer.isVisible = true
                        imageryLayer.opacity = 1.0f
                        Timber.d("🛰️ Satellite base layer - visible: ${imageryLayer.isVisible}, opacity: ${imageryLayer.opacity}")
                        
                        // إنشاء خريطة هجينة مع ضمان ظهور الستلايت
                        val hybridBasemap = Basemap().apply {
                            // إضافة الستلايت كطبقة أساسية
                            baseLayers.add(imageryLayer)
                        }
                        
                        val map = ArcGISMap(hybridBasemap).apply {
                            minScale = 10000000.0  // زوم أكثر بُعداً
                            maxScale = 500.0       // زوم أكثر قرباً
                        }
                        
                        // إضافة VTPK كطبقة عملياتية (فوق الستلايت) بدلاً من reference
                        // هذا يضمن أن التسميات تظهر فوق الستلايت وليس تحتها
                        map.operationalLayers.add(vectorLabelsLayer)
                        
                        Timber.d("🗺️ Hybrid map created - Base: ${hybridBasemap.baseLayers.size}, Operational: ${map.operationalLayers.size}")
                        
                        // تعيين الخريطة بشكل مستقر
                        views.esriMapView.post {
                            views.esriMapView.map = map
                            
                            map.addDoneLoadingListener {
                                Timber.d("🔍 Hybrid map loaded - Base layers: ${map.basemap.baseLayers.size}, Operational layers: ${map.operationalLayers.size}")
                                
                                // فحص حالة طبقات الأساس (الستلايت)
                                map.basemap.baseLayers.forEach { layer ->
                                    Timber.d("🛰️ Base layer: ${layer.javaClass.simpleName}, visible: ${layer.isVisible}, opacity: ${layer.opacity}, loaded: ${layer.loadStatus}")
                                }
                                
                                // فحص حالة الطبقات العملياتية (التسميات)
                                map.operationalLayers.forEach { layer ->
                                    Timber.d("🏷️ Operational layer: ${layer.name}, visible: ${layer.isVisible}, opacity: ${layer.opacity}, loaded: ${layer.loadStatus}")
                                }
                                
                                // التأكد من استقرار الخريطة قبل إظهارها
                                views.esriMapView.post {
                                    centerMapOnUae()
                                    views.loadingView.isVisible = false
                                    views.esriMapView.visibility = View.VISIBLE
                                    
                                    // فحص نهائي لحالة MapView
                                    Timber.d("🔍 Final MapView state - Visibility: ${views.esriMapView.visibility}, Width: ${views.esriMapView.width}, Height: ${views.esriMapView.height}")
                                    
                                    // إجبار إعادة رسم بعد التأكد من الاستقرار
                                    views.esriMapView.requestLayout()
                                    views.esriMapView.invalidate()
                                    
                                    Timber.d("✅ Satellite + VTPK labels loaded and displayed successfully")
                                    isLoadingMap = false  // انتهاء التحميل
                                }
                            }
                        }
                    } else {
                        Timber.e("❌ Failed to load VTPK labels: ${vectorLabelsLayer.loadError?.message}")
                        loadSatelliteOnly(tileCache)
                    }
                }
                vectorLabelsLayer.loadAsync()
            } else {
                Timber.w("⚠️ No VTPK labels available, loading satellite only")
                loadSatelliteOnly(tileCache)
            }
        } catch (e: Exception) {
            Timber.e(e, "❌ Failed to load satellite with VTPK labels")
            loadSatelliteOnly(tileCache)
        }
    }



    /**
     * 🛰️ تحميل صور الستلايت فقط (بدون تسميات)
     */
    private fun loadSatelliteOnly(tileCache: TileCache) {
        try {
            Timber.d("🛰️ Loading satellite imagery only")
            
            val imageryLayer = ArcGISTiledLayer(tileCache)
            
            // فحص تفصيلي لطبقة الستلايت
            imageryLayer.addDoneLoadingListener {
                val loadStatus = imageryLayer.loadStatus
                Timber.d("🛰️ Satellite-only layer status: $loadStatus")
                Timber.d("🛰️ Satellite-only layer details - visible: ${imageryLayer.isVisible}, opacity: ${imageryLayer.opacity}")
                
                if (loadStatus != LoadStatus.LOADED) {
                    val error = imageryLayer.loadError
                    Timber.e("❌ Satellite-only layer failed: ${error?.message}")
                }
            }
            
            // التأكد من إعدادات الرؤية
            imageryLayer.isVisible = true
            imageryLayer.opacity = 1.0f
            
            val basemap = Basemap(imageryLayer)
            val map = ArcGISMap(basemap).apply {
                // إعدادات أكثر مرونة للزوم لضمان ظهور الستلايت
                minScale = 10000000.0  // زوم أكثر بُعداً 
                maxScale = 500.0       // زوم أكثر قرباً
            }
            
            // فحص حالة الخريطة
            Timber.d("🗺️ Creating satellite-only map with basemap layers: ${basemap.baseLayers.size}")
            
            // تعيين الخريطة بشكل مستقر
            views.esriMapView.post {
                views.esriMapView.map = map
                
                map.addDoneLoadingListener {
                    views.esriMapView.post {
                        centerMapOnUae()
                        views.loadingView.isVisible = false
                        views.esriMapView.visibility = View.VISIBLE
                        
                        // فحص نهائي لحالة MapView
                        Timber.d("🔍 Final MapView state (satellite-only) - Visibility: ${views.esriMapView.visibility}, Width: ${views.esriMapView.width}, Height: ${views.esriMapView.height}")
                        
                        // إجبار إعادة رسم
                        views.esriMapView.requestLayout()
                        views.esriMapView.invalidate()
                        
                        Timber.d("✅ Satellite only loaded and displayed successfully")
                        isLoadingMap = false  // انتهاء التحميل
                    }
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "❌ Failed to load satellite only")
            showError("خطأ في تحميل صور الستلايت: ${e.message}")
        }
    }

    /**
     * 🔄 محاولة استخدام VTPK كطبقة تسميات على الستلايت
     */
    private fun tryVtpkLabelsAsFallback(satellitePath: String) {
        try {
            Timber.d("🔄 Trying VTPK labels as fallback")

            val vtpkPath = vtpkProvider.getHybridLabelsPath()
            if (!vtpkPath.isNullOrEmpty() && vtpkPath.endsWith(".vtpk")) {
                Timber.d("🏷️ Loading VTPK labels from: $vtpkPath")

                // التحقق من وجود ملف VTPK
                val vtpkFile = java.io.File(vtpkPath)
                if (!vtpkFile.exists()) {
                    Timber.e("❌ VTPK file does not exist: $vtpkPath")
                    showSatelliteOnly(satellitePath)
                    return
                }

                Timber.d("📁 VTPK file exists, size: ${vtpkFile.length()} bytes")

                // إنشاء طبقة صور جديدة لتجنب "already owned" error
                val newImageryLayer = ArcGISTiledLayer(TileCache(satellitePath))
                val labelsLayer = ArcGISVectorTiledLayer(vtpkPath)

                // مراقبة تحميل طبقة التسميات
                labelsLayer.addDoneLoadingListener {
                    val loadStatus = labelsLayer.loadStatus
                    Timber.d("🏷️ VTPK labels layer load status: $loadStatus")

                    if (loadStatus != LoadStatus.LOADED) {
                        val error = labelsLayer.loadError
                        Timber.e("❌ Failed to load VTPK labels: ${error?.message}")
                        showSatelliteOnly(satellitePath)
                        return@addDoneLoadingListener
                    }

                    Timber.d("✅ VTPK labels layer loaded successfully")
                }

                val hybridBasemap = Basemap().apply {
                    baseLayers.add(newImageryLayer)
                    referenceLayers.add(labelsLayer)
                }

                val hybridMap = ArcGISMap(hybridBasemap)

                // 🎯 تطبيق قيود المقياس
                hybridMap.minScale = 1000000.0 // 1:1M scale (zoom out limit)
                hybridMap.maxScale = 1000.0    // 1:1K scale (zoom in limit)

                views.esriMapView.map = hybridMap

                hybridMap.addDoneLoadingListener {
                    val mapLoadStatus = hybridMap.loadStatus
                    Timber.d("🗺️ Hybrid map load status: $mapLoadStatus")

                    if (mapLoadStatus == LoadStatus.LOADED) {
                        // 🎯 إظهار الخريطة أولاً ثم تعديل الموقع
                        views.loadingView.isVisible = false
                        views.esriMapView.visibility = View.VISIBLE

                        // 🔍 التحقق من حالة MapView بالتفصيل
                        Timber.d("🖥️ MapView visibility: ${views.esriMapView.visibility}")
                        Timber.d("🔄 LoadingView visibility: ${views.loadingView.isVisible}")
                        Timber.d("📱 Map set to MapView: ${views.esriMapView.map != null}")
                        Timber.d("🎯 Initial map scale: ${views.esriMapView.mapScale}")

                        // إجبار إعادة رسم MapView
                        views.esriMapView.requestLayout()
                        views.esriMapView.invalidate()

                        // الآن حدد موقع الإمارات
                        centerMapOnUae()

                        Timber.d("✅ Satellite + VTPK labels loaded as fallback")
                        Timber.d("🎯 Map scale limits: min=${hybridMap.minScale}, max=${hybridMap.maxScale}")
                        Timber.d("🔄 MapView refresh and center requested")
                    } else {
                        val mapError = hybridMap.loadError
                        Timber.e("❌ Hybrid map failed to load: ${mapError?.message}")
                        showSatelliteOnly(satellitePath)
                    }
                }
            } else {
                Timber.w("⚠️ No VTPK labels available, showing satellite only")
                showSatelliteOnly(satellitePath)
            }
        } catch (e: Exception) {
            Timber.e(e, "❌ VTPK fallback also failed")
            showSatelliteOnly(satellitePath)
        }
    }

    /**
     * 🛰️ إظهار صور الستلايت فقط بدون تسميات (fallback)
     */
    private fun showSatelliteOnly(satellitePath: String) {
        try {
            Timber.d("🔄 Showing satellite only as fallback from: $satellitePath")

            // إنشاء طبقة صور جديدة
            val imageryLayer = ArcGISTiledLayer(TileCache(satellitePath))

            // مراقبة تحميل طبقة الصور
            imageryLayer.addDoneLoadingListener {
                val loadStatus = imageryLayer.loadStatus
                Timber.d("🛰️ Satellite-only layer load status: $loadStatus")

                if (loadStatus != LoadStatus.LOADED) {
                    val error = imageryLayer.loadError
                    Timber.e("❌ Satellite-only layer failed: ${error?.message}")
                    // إذا فشلت حتى الصور، اعرض خريطة أساسية
                    showBasicFallbackMap()
                    return@addDoneLoadingListener
                }

                Timber.d("✅ Satellite-only layer loaded successfully")
            }

            val basemap = Basemap(imageryLayer)
            val map = ArcGISMap(basemap)

            // 🎯 تطبيق قيود المقياس
            map.minScale = 1000000.0 // 1:1M scale (zoom out limit)
            map.maxScale = 1000.0    // 1:1K scale (zoom in limit)

            views.esriMapView.map = map

            map.addDoneLoadingListener {
                val mapLoadStatus = map.loadStatus
                Timber.d("🗺️ Satellite-only map load status: $mapLoadStatus")

                if (mapLoadStatus == LoadStatus.LOADED) {
                    // 🎯 إظهار الخريطة أولاً ثم تعديل الموقع
                    views.loadingView.isVisible = false
                    views.esriMapView.visibility = View.VISIBLE

                    // 🔍 التحقق من حالة MapView بالتفصيل
                    Timber.d("🖥️ MapView visibility (satellite): ${views.esriMapView.visibility}")
                    Timber.d("🔄 LoadingView visibility (satellite): ${views.loadingView.isVisible}")
                    Timber.d("📱 Map set to MapView (satellite): ${views.esriMapView.map != null}")
                    Timber.d("🎯 Initial map scale (satellite): ${views.esriMapView.mapScale}")

                    // إجبار إعادة رسم MapView
                    views.esriMapView.requestLayout()
                    views.esriMapView.invalidate()

                    // الآن حدد موقع الإمارات
                    centerMapOnUae()

                    Timber.d("✅ Satellite only loaded successfully")
                    Timber.d("🎯 Map scale limits: min=${map.minScale}, max=${map.maxScale}")
                    Timber.d("🔄 MapView refresh and center requested (satellite)")
                } else {
                    val mapError = map.loadError
                    Timber.e("❌ Satellite-only map failed to load: ${mapError?.message}")
                    showBasicFallbackMap()
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "❌ Even satellite only failed")
            showBasicFallbackMap()
        }
    }

    /**
     * 🆘 آخر محاولة: خريطة أساسية من الإنترنت
     */
    private fun showBasicFallbackMap() {
        try {
            Timber.d("🆘 Loading basic fallback map")

            // خريطة أساسية بسيطة
            val fallbackBasemap = Basemap.createStreets()
            val fallbackMap = ArcGISMap(fallbackBasemap)

            // تطبيق قيود المقياس
            fallbackMap.minScale = 1000000.0
            fallbackMap.maxScale = 1000.0

            views.esriMapView.map = fallbackMap

            fallbackMap.addDoneLoadingListener {
                centerMapOnUae()
                Timber.d("✅ Basic fallback map loaded")

                // 🔍 التحقق من حالة MapView بالتفصيل
                Timber.d("🖥️ MapView visibility before (fallback): ${views.esriMapView.visibility}")
                Timber.d("🔄 LoadingView visibility before (fallback): ${views.loadingView.isVisible}")
                Timber.d("📱 Map set to MapView (fallback): ${views.esriMapView.map != null}")

                views.loadingView.isVisible = false
                views.esriMapView.visibility = View.VISIBLE

                Timber.d("🖥️ MapView visibility after (fallback): ${views.esriMapView.visibility}")
                Timber.d("🔄 LoadingView visibility after (fallback): ${views.loadingView.isVisible}")

                // 🎯 إجبار إعادة رسم MapView
                views.esriMapView.requestLayout()
                views.esriMapView.invalidate()

                Timber.d("🔄 MapView refresh requested (fallback)")
            }
        } catch (e: Exception) {
            Timber.e(e, "❌ Even basic fallback failed")
            // آخر محاولة: إظهار رسالة خطأ وإخفاء loading
            views.loadingView.isVisible = false
            views.esriMapView.visibility = View.VISIBLE
            showError("فشل في تحميل الخريطة")
        }
    }

    private fun showMapControls() {
        views.mapControlPanel.visibility = View.VISIBLE

        // Update info panel
        updateMapInfo()
    }

    private fun observeViewEvents() {
        viewModel.observeViewEvents { event ->
            when (event) {
                is GroupMapViewEvents.MapLoaded -> {
                    Timber.d("Map loaded successfully")
                }
                is GroupMapViewEvents.MapLoadError -> {
                    Timber.e("Map load error: ${event.message}")
                    showError(event.message)
                }
                is GroupMapViewEvents.MapStyleSwitched -> {
                    Timber.d("Map style switched to: ${event.style}")
                    updateMapInfo()
                }
                is GroupMapViewEvents.MembersLoaded -> {
                    Timber.d("Members loaded: ${event.members.size}")
                    // TODO: Display members on the map later
                }
            }
        }
    }

    private fun updateMapInfo() {
//        val vtpkProvider = viewModel.getVtpkProvider()
//        val currentStyle = vtpkProvider.getCurrentStyle()
//        val styleName = vtpkProvider.getStyleDisplayName(currentStyle)
//        views.currentStyleText.text = styleName
    }

    private fun switchMapStyle(newStyle: String) {
        try {
            val vtpkProvider = viewModel.getVtpkProvider()
            val vtpkPath = vtpkProvider.getMapPathForStyle(newStyle)
            if (vtpkPath != null) {

                val layer = ArcGISVectorTiledLayer(vtpkPath)
                val basemap = Basemap(layer)
                val map = ArcGISMap(basemap)

                // Set map constraints
                map.minScale = 1000000.0
                map.maxScale = 1000.0

                views.esriMapView.map = map

                // Keep current viewpoint
                val currentViewpoint = views.esriMapView.getCurrentViewpoint(Viewpoint.Type.CENTER_AND_SCALE)
                if (currentViewpoint != null) {
                    views.esriMapView.setViewpointAsync(currentViewpoint)
                }

                // Notify ViewModel
                viewModel.handle(GroupMapAction.SwitchMapStyle(newStyle))

                Timber.d("Switched to map style: $newStyle")
            } else {
                Timber.e("VTPK file not found for style: $newStyle")
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to switch map style")
        }
    }

    private fun showError(msg: String) {
        isLoadingMap = false  // إعادة تعيين العلم عند الخطأ
        views.loadingView.isVisible = false
        views.errorView.isVisible = true
        views.errorText.text = msg
        views.esriMapView.visibility = View.GONE
        views.mapControlPanel.visibility = View.GONE
//        views.mapInfoPanel.visibility = View.GONE
        // Notify ViewModel of error
        viewModel.handle(GroupMapAction.LoadMembers) // أو MapLoadError حسب حدثك
    }

    // دورة حياة ArcGIS MapView
    override fun onResume() {
        super.onResume(); views.esriMapView.resume()
    }

    override fun onPause() {
        views.esriMapView.pause(); super.onPause()
    }

    override fun onDestroyView() {
        views.esriMapView.dispose(); super.onDestroyView()
    }

    override fun invalidate() {
        withState(viewModel) { state ->
            views.loadingView.isVisible = state.isLoading
            views.errorView.isVisible = state.error != null
            state.error?.let { views.errorText.text = it }

            // Update map info if map is loaded
            if (state.isMapLoaded && state.error == null) {
                updateMapInfo()
            }
        }
    }
}
