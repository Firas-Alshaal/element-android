/*
 * Copyright 2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.roomprofile.groupmap

import android.content.Context
import android.content.res.AssetManager
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import javax.inject.Inject

/**
 * Provider for managing VTPK (Vector Tile Package) files for offline maps.
 * Copies VTPK files from assets to internal storage for use by the map SDK.
 */
class VtpkMapProvider @Inject constructor(
        @ApplicationContext private val context: Context
) {
    companion object {
        private const val VTPK_ASSETS_DIR = "maps"
        private const val VTPK_INTERNAL_DIR = "vtpk_maps"

        // Map style constants - updated to match actual available files
        const val STYLE_STREETS = "streets"  // Will use HYBRID as fallback
        const val STYLE_SATELLITE = "satellite"
        const val STYLE_HYBRID = "hybrid"
        const val STYLE_MMPK = "mmpk"  // Mobile Map Package - الحل الأمثل!

        // VTPK file mappings for different styles - updated to match actual files
        private val STYLE_TO_FILENAME = mapOf(
                STYLE_STREETS to "Hybrid Reference Layer_30B0353F-0C0E-4524-A93F-2EFC6D2D3002.vtpk",
                STYLE_SATELLITE to "World Imagery_6E8F1887-C3FE-4601-AF3B-C6322F7512F8.tpkx",
                STYLE_HYBRID to "Hybrid Reference Layer_30B0353F-0C0E-4524-A93F-2EFC6D2D3002.vtpk",
                STYLE_MMPK to "Map.mmpk",
        )
    }

    private var currentStyle = STYLE_HYBRID

    fun areVtpkFilesAvailable(): Boolean {
        val dir = File(context.filesDir, VTPK_INTERNAL_DIR)
        return STYLE_TO_FILENAME.values.all { filename ->
            File(dir, filename).exists()
        }
    }

    fun getDefaultMapPath(): String? = getMapPathForStyle(currentStyle)

    fun getUaeMapPath(): String? = getMapPathForStyle(STYLE_HYBRID)

    // Add specific getter methods for each style
    fun getStreetsMapPath(): String? = getMapPathForStyle(STYLE_STREETS)
    fun getSatelliteMapPath(): String? = getMapPathForStyle(STYLE_SATELLITE)
    fun getHybridMapPath(): String? = getMapPathForStyle(STYLE_HYBRID)
    fun getHybridLabelsPath(): String? {
        // أولاً جرب الـ VTPK الهجين (هو الأكثر احتمالاً لوجود تسميات)
        val hybridPath = getMapPathForStyle(STYLE_HYBRID)
        if (!hybridPath.isNullOrEmpty()) {
            return hybridPath
        }
        
        // إذا لم يوجد، جرب الـ streets
        val streetsPath = getMapPathForStyle(STYLE_STREETS)
        if (!streetsPath.isNullOrEmpty()) {
            return streetsPath
        }
        
        // كحل أخير، ارجع أي VTPK متاح
        return STYLE_TO_FILENAME.values.find { filename ->
            filename.endsWith(".vtpk") && getInternalPath(filename) != null
        }?.let { getInternalPath(it) }
    }
    fun getMmpkMapPath(): String? = getMapPathForStyle(STYLE_MMPK) // Mobile Map Package

    fun getMapPathForStyle(style: String): String? {
        val filename = STYLE_TO_FILENAME[style] ?: return null
        return getInternalPath(filename)
    }

    fun getAvailableStyles(): List<String> = STYLE_TO_FILENAME.keys.toList()

    fun getCurrentStyle(): String = currentStyle

    fun setCurrentStyle(style: String): Boolean {
        return if (STYLE_TO_FILENAME.containsKey(style)) {
            currentStyle = style
            true
        } else {
            false
        }
    }

    fun getStyleDisplayName(style: String): String {
        return when (style) {
            STYLE_STREETS -> "Streets"
            STYLE_SATELLITE -> "Satellite"
            STYLE_HYBRID -> "Hybrid"
            STYLE_MMPK -> "Complete Map"  // خريطة متكاملة
            else -> "Unknown"
        }
    }

    private fun getInternalPath(filename: String): String? {
        val dir = File(context.filesDir, VTPK_INTERNAL_DIR)
        val f = File(dir, filename)
        return if (f.exists()) f.absolutePath else null
    }

    fun copyVtpkFilesFromAssets() {
        val am = context.assets
        val dir = File(context.filesDir, VTPK_INTERNAL_DIR).apply { mkdirs() }
        
        var successCount = 0
        var totalCount = STYLE_TO_FILENAME.size
        
        STYLE_TO_FILENAME.values.forEach { filename ->
            if (copyAssetFile(am, filename, dir)) {
                successCount++
            }
        }
        
        Timber.d("VTPK files copied: $successCount/$totalCount to: ${dir.absolutePath}")
        
        if (successCount == 0) {
            Timber.e("No VTPK files were copied successfully!")
        }
    }

    private fun copyAssetFile(assetManager: AssetManager, filename: String, targetDir: File): Boolean {
        val targetFile = File(targetDir, filename)
        if (targetFile.exists()) {
            Timber.d("Exists: ${targetFile.absolutePath}")
            return true
        }
        
        try {
            // Check if file exists in assets first
            val assetPath = "$VTPK_ASSETS_DIR/$filename"
            val assetList = assetManager.list(VTPK_ASSETS_DIR) ?: emptyArray()
            
            if (!assetList.contains(filename)) {
                Timber.e("Asset file not found: $assetPath")
                return false
            }
            
            // لاحظ استخدام مجلد الأصول "maps/"
            assetManager.open(assetPath).use { input ->
                FileOutputStream(targetFile).use { output ->
                    input.copyTo(output)
                }
            }
            Timber.d("Copied $filename -> ${targetFile.absolutePath}")
            return true
        } catch (e: IOException) {
            Timber.e(e, "Failed to copy $filename from assets")
            return false
        }
    }
}
