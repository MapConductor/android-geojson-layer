package com.mapconductor.geojson

sealed class GeoJSONGeometry {
    data class Point(val longitude: Double, val latitude: Double) : GeoJSONGeometry()
    data class MultiPoint(val points: List<Point>) : GeoJSONGeometry()
    data class LineString(val coordinates: List<LonLat>) : GeoJSONGeometry()
    data class MultiLineString(val lines: List<List<LonLat>>) : GeoJSONGeometry()

    /**
     * Polygon rings in GeoJSON order: first ring is the exterior, subsequent rings are holes.
     */
    data class Polygon(val rings: List<List<LonLat>>) : GeoJSONGeometry()
    data class MultiPolygon(val polygons: List<List<List<LonLat>>>) : GeoJSONGeometry()
    data class GeometryCollection(val geometries: List<GeoJSONGeometry>) : GeoJSONGeometry()
    object Empty : GeoJSONGeometry()
}

data class LonLat(val longitude: Double, val latitude: Double)
