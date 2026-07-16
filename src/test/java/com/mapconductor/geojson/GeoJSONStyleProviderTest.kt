package com.mapconductor.geojson

import org.junit.Assert.assertEquals
import org.junit.Test

class GeoJSONStyleProviderTest {
    @Test
    fun defaultProviderUsesFeatureValuesBeforeLayerDefaults() {
        val defaults = GeoJSONTileRenderer.LayerStyle(1, 2, 3f, 4f)
        val feature =
            GeoJSONFeature(
                geometry = GeoJSONGeometry.Empty,
                strokeColor = 10,
                pointRadius = 40f,
            )

        val style = DefaultGeoJSONStyleProvider.getStyle(feature, defaults)

        assertEquals(10, style.strokeColor)
        assertEquals(2, style.fillColor)
        assertEquals(3f, style.strokeWidth)
        assertEquals(40f, style.pointRadius)
    }
}
