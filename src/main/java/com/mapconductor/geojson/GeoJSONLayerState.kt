package com.mapconductor.geojson

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.mapconductor.core.features.GeoPoint
import android.graphics.Color

class GeoJSONLayerState(
    opacity: Float = GeoJSONDefaults.DEFAULT_OPACITY,
    strokeColor: Int = GeoJSONDefaults.DEFAULT_STROKE_COLOR,
    fillColor: Int = GeoJSONDefaults.DEFAULT_FILL_COLOR,
    strokeWidth: Float = GeoJSONDefaults.DEFAULT_STROKE_WIDTH,
    pointRadius: Float = GeoJSONDefaults.DEFAULT_POINT_RADIUS,
    visible: Boolean = true,
    minZoom: Int = 0,
    maxZoom: Int = 22,
    val onClick: ((feature: GeoJSONFeature, position: GeoPoint) -> Unit)? = null,
) {
    var opacity by mutableStateOf(opacity)
    var strokeColor by mutableStateOf(strokeColor)
    var fillColor by mutableStateOf(fillColor)
    var strokeWidth by mutableStateOf(strokeWidth)
    var pointRadius by mutableStateOf(pointRadius)
    var visible by mutableStateOf(visible)
    var minZoom by mutableStateOf(minZoom)
    var maxZoom by mutableStateOf(maxZoom)

    internal var renderer: GeoJSONTileRenderer? = null

    /**
     * Call this from your map's onMapClick handler to perform feature hit-testing.
     * If a feature is found at [geoPoint], the [onClick] callback is invoked
     * and true is returned.
     */
    fun processClick(geoPoint: GeoPoint): Boolean {
        val r = renderer ?: return false
        val feature = r.hitTest(geoPoint.longitude, geoPoint.latitude) ?: return false
        onClick?.invoke(feature, geoPoint)
        return true
    }

    fun copy(
        opacity: Float = this.opacity,
        strokeColor: Int = this.strokeColor,
        fillColor: Int = this.fillColor,
        strokeWidth: Float = this.strokeWidth,
        pointRadius: Float = this.pointRadius,
        visible: Boolean = this.visible,
        minZoom: Int = this.minZoom,
        maxZoom: Int = this.maxZoom,
        onClick: ((GeoJSONFeature, GeoPoint) -> Unit)? = this.onClick,
    ): GeoJSONLayerState =
        GeoJSONLayerState(
            opacity = opacity,
            strokeColor = strokeColor,
            fillColor = fillColor,
            strokeWidth = strokeWidth,
            pointRadius = pointRadius,
            visible = visible,
            minZoom = minZoom,
            maxZoom = maxZoom,
            onClick = onClick,
        )
}

object GeoJSONDefaults {
    const val DEFAULT_OPACITY = 1.0f
    val DEFAULT_STROKE_COLOR: Int = Color.argb(255, 30, 136, 229)
    val DEFAULT_FILL_COLOR: Int = Color.argb(128, 30, 136, 229)
    const val DEFAULT_STROKE_WIDTH = 2f
    const val DEFAULT_POINT_RADIUS = 8f
    const val DEFAULT_TILE_SIZE = 512
    const val DEFAULT_MAX_ZOOM = 22
}
