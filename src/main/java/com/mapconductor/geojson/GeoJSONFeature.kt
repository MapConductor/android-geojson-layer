package com.mapconductor.geojson

/**
 * Lightweight, non-Compose data class for static/bulk GeoJSON features.
 * Use this (instead of [GeoJSONFeatureState]) when loading large GeoJSON files
 * that don't need per-feature reactive state — e.g. via [GeoJSONParser.parseStream].
 */
data class GeoJSONFeature(
    val id: String? = null,
    val geometry: GeoJSONGeometry,
    val properties: Map<String, Any?> = emptyMap(),
    val strokeColor: Int? = null,
    val fillColor: Int? = null,
    val strokeWidth: Float? = null,
    val pointRadius: Float? = null,
    val visible: Boolean = true,
)
