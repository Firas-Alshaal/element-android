/*
 * Copyright 2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.location

import javax.inject.Inject

class OfflineMapProvider @Inject constructor() {
    
    fun getOfflineMapStyleJson(): String {
        // خريطة محلية محسنة مع خطوط الشبكة والمناطق الأساسية
        return """
        {
            "version": 8,
            "name": "Enhanced Offline Group Map",
            "metadata": {
                "mapbox:autocomposite": true,
                "mapbox:type": "template"
            },
            "sources": {
                "grid-source": {
                    "type": "geojson",
                    "data": {
                        "type": "FeatureCollection",
                        "features": []
                    }
                }
            },
            "layers": [
                {
                    "id": "background",
                    "type": "background",
                    "paint": {
                        "background-color": "#f0f8ff"
                    }
                },
                {
                    "id": "grid-lines",
                    "type": "line",
                    "source": "grid-source",
                    "paint": {
                        "line-color": "#e0e0e0",
                        "line-width": 0.5,
                        "line-opacity": 0.6
                    }
                },
                {
                    "id": "water-areas",
                    "type": "fill",
                    "source": "grid-source",
                    "filter": ["==", ["get", "type"], "water"],
                    "paint": {
                        "fill-color": "#b3d9ff",
                        "fill-opacity": 0.7
                    }
                },
                {
                    "id": "land-areas",
                    "type": "fill", 
                    "source": "grid-source",
                    "filter": ["==", ["get", "type"], "land"],
                    "paint": {
                        "fill-color": "#f5f5dc",
                        "fill-opacity": 0.3
                    }
                },
                {
                    "id": "major-roads",
                    "type": "line",
                    "source": "grid-source", 
                    "filter": ["==", ["get", "type"], "road"],
                    "paint": {
                        "line-color": "#ffffff",
                        "line-width": 2,
                        "line-opacity": 0.8
                    }
                },
                {
                    "id": "buildings",
                    "type": "fill",
                    "source": "grid-source",
                    "filter": ["==", ["get", "type"], "building"], 
                    "paint": {
                        "fill-color": "#d3d3d3",
                        "fill-opacity": 0.6,
                        "fill-outline-color": "#a9a9a9"
                    }
                }
            ],
            "zoom": 10,
            "center": [0, 0],
            "bearing": 0,
            "pitch": 0
        }
        """.trimIndent()
    }
    
    /**
     * Alternative simpler style that should work better with Mapbox offline parsing
     */
    fun getSimpleOfflineMapStyleJson(): String {
        return """
        {
            "version": 8,
            "name": "Simple Offline Map",
            "sources": {},
            "layers": [
                {
                    "id": "background",
                    "type": "background",
                    "paint": {
                        "background-color": "#f0f8ff"
                    }
                }
            ]
        }
        """.trimIndent()
    }
} 