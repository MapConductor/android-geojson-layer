package com.mapconductor.geojson

/** Resolves the render style for a GeoJSON feature. */
fun interface GeoJSONStyleProviderInterface {
    /**
     * Returns the complete style for [feature]. [defaultStyle] contains the current layer-level
     * stroke color, fill color, stroke width, and point radius.
     */
    fun getStyle(
        feature: GeoJSONFeature,
        defaultStyle: GeoJSONTileRenderer.LayerStyle,
    ): GeoJSONTileRenderer.LayerStyle
}

/** Preserves the existing feature-style-over-layer-style behavior. */
object DefaultGeoJSONStyleProvider : GeoJSONStyleProviderInterface {
    override fun getStyle(
        feature: GeoJSONFeature,
        defaultStyle: GeoJSONTileRenderer.LayerStyle,
    ): GeoJSONTileRenderer.LayerStyle =
        GeoJSONTileRenderer.LayerStyle(
            strokeColor = feature.strokeColor ?: defaultStyle.strokeColor,
            fillColor = feature.fillColor ?: defaultStyle.fillColor,
            strokeWidth = feature.strokeWidth ?: defaultStyle.strokeWidth,
            pointRadius = feature.pointRadius ?: defaultStyle.pointRadius,
        )
}
