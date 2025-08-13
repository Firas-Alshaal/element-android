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

//        private const val UAE_MAP_FILENAME =
//                "OpenStreetMap_F9A0B63D-2DDB-47F1-B1CC-BCFCB403DC14.vtpk"
//
//        private const val NOVA_MAP_FILENAME =
//                "Nova_9233B27F-012A-4847-8687-8BD434431EDF.vtpk"
//
        private const val UAE_MAP_FILENAME =
                "Hybrid Reference Layer_A23420F5-F12E-46F0-B1FF-5AD75A8A9BA8.vtpk"

//        private const val UAE_MAP_FILENAME =
//                "World Imagery_4CCE3C45-1863-494E-B47E-EECE00365956.tpkx"
    }

    fun areVtpkFilesAvailable(): Boolean {
        val dir = File(context.filesDir, VTPK_INTERNAL_DIR)
        return File(dir, UAE_MAP_FILENAME).exists() && File(dir, UAE_MAP_FILENAME).exists()
    }

    fun getDefaultMapPath(): String? = getUaeMapPath()

    fun getUaeMapPath(): String? = getInternalPath(UAE_MAP_FILENAME)
    fun getNovaMapPath(): String? = getInternalPath(UAE_MAP_FILENAME)

    private fun getInternalPath(filename: String): String? {
        val dir = File(context.filesDir, VTPK_INTERNAL_DIR)
        val f = File(dir, filename)
        return if (f.exists()) f.absolutePath else null
    }

    fun copyVtpkFilesFromAssets() {
        val am = context.assets
        val dir = File(context.filesDir, VTPK_INTERNAL_DIR).apply { mkdirs() }
        copyAssetFile(am, UAE_MAP_FILENAME, dir)
        copyAssetFile(am, UAE_MAP_FILENAME, dir)
        Timber.d("VTPK files ensured in: ${dir.absolutePath}")
    }

    private fun copyAssetFile(assetManager: AssetManager, filename: String, targetDir: File) {
        val targetFile = File(targetDir, filename)
        if (targetFile.exists()) {
            Timber.d("Exists: ${targetFile.absolutePath}")
            return
        }
        try {
            // لاحظ استخدام مجلد الأصول "maps/"
            assetManager.open("$VTPK_ASSETS_DIR/$filename").use { input ->
                FileOutputStream(targetFile).use { output ->
                    input.copyTo(output)
                }
            }
            Timber.d("Copied $filename -> ${targetFile.absolutePath}")
        } catch (e: IOException) {
            Timber.e(e, "Failed to copy $filename from assets")
        }
    }
}
