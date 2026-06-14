package com.mapconductor.geojson

import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.io.InputStreamReader
import java.io.StringWriter
import android.util.JsonReader
import android.util.JsonToken
import android.util.JsonWriter

object GeoJSONParser {
    /**
     * Streams a GeoJSON InputStream without loading the entire file into memory.
     * Uses [JsonReader] for incremental parsing — suitable for files 10 MB+.
     * Handles FeatureCollection at the top level; bare Feature/geometry is not supported.
     */
    fun parseStream(inputStream: InputStream): List<GeoJSONFeature> {
        val result = mutableListOf<GeoJSONFeature>()
        streamParse(inputStream) { result.add(it) }
        return result
    }

    /**
     * Streams GeoJSON features one at a time, invoking [onFeature] for each.
     * Peak memory is dominated by a single feature rather than the full file.
     */
    fun streamParse(
        inputStream: InputStream,
        onFeature: (GeoJSONFeature) -> Unit,
    ) {
        val reader = JsonReader(InputStreamReader(inputStream, Charsets.UTF_8))
        reader.isLenient = true
        reader.use { parseTopLevelStreaming(it, onFeature) }
    }

    private fun parseTopLevelStreaming(
        reader: JsonReader,
        onFeature: (GeoJSONFeature) -> Unit,
    ) {
        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "features" -> {
                    reader.beginArray()
                    while (reader.hasNext()) featureFromReader(reader)?.let(onFeature)
                    reader.endArray()
                }
                else -> reader.skipValue()
            }
        }
        reader.endObject()
    }

    private fun featureFromReader(reader: JsonReader): GeoJSONFeature? {
        val sw = StringWriter()
        val jw = JsonWriter(sw)
        copyJsonValue(reader, jw)
        jw.flush()
        val obj = runCatching { JSONObject(sw.toString()) }.getOrNull() ?: return null
        return parseFeatureAsData(obj)
    }

    internal fun parseFeatureAsData(obj: JSONObject): GeoJSONFeature? {
        val geometryObj = obj.optJSONObject("geometry") ?: return null
        val geometry = parseGeometryObject(geometryObj) ?: return null
        val id = if (obj.has("id")) obj.get("id").toString() else null
        val properties = mutableMapOf<String, Any?>()
        obj.optJSONObject("properties")?.let { props ->
            for (key in props.keys()) {
                properties[key] = props.get(key).let { if (it == JSONObject.NULL) null else it }
            }
        }
        return GeoJSONFeature(id = id, geometry = geometry, properties = properties)
    }

    private fun copyJsonValue(
        reader: JsonReader,
        writer: JsonWriter,
    ) {
        when (reader.peek()) {
            JsonToken.BEGIN_OBJECT -> {
                writer.beginObject()
                reader.beginObject()
                while (reader.hasNext()) {
                    writer.name(reader.nextName())
                    copyJsonValue(reader, writer)
                }
                reader.endObject()
                writer.endObject()
            }
            JsonToken.BEGIN_ARRAY -> {
                writer.beginArray()
                reader.beginArray()
                while (reader.hasNext()) copyJsonValue(reader, writer)
                reader.endArray()
                writer.endArray()
            }
            JsonToken.STRING -> writer.value(reader.nextString())
            JsonToken.NUMBER -> writer.value(reader.nextDouble())
            JsonToken.BOOLEAN -> writer.value(reader.nextBoolean())
            JsonToken.NULL -> {
                reader.nextNull()
                writer.nullValue()
            }
            else -> reader.skipValue()
        }
    }

    /**
     * Parses a GeoJSON string and returns a list of GeoJSONFeatureState objects.
     * Accepts Feature and FeatureCollection at the top level.
     */
    fun parse(json: String): List<GeoJSONFeatureState> {
        val obj = runCatching { JSONObject(json) }.getOrNull() ?: return emptyList()
        return when (obj.optString("type")) {
            "FeatureCollection" -> parseFeatureCollection(obj)
            "Feature" -> listOfNotNull(parseFeature(obj))
            else -> {
                val geometry = parseGeometryObject(obj) ?: return emptyList()
                listOf(GeoJSONFeatureState(geometry = geometry))
            }
        }
    }

    /**
     * Parses a single GeoJSON Feature string into a GeoJSONFeatureState.
     */
    fun parseFeature(json: String): GeoJSONFeatureState? {
        val obj = runCatching { JSONObject(json) }.getOrNull() ?: return null
        return parseFeature(obj)
    }

    /**
     * Parses a GeoJSON geometry string into a GeoJSONGeometry.
     */
    fun parseGeometry(json: String): GeoJSONGeometry? {
        val obj = runCatching { JSONObject(json) }.getOrNull() ?: return null
        return parseGeometryObject(obj)
    }

    private fun parseFeatureCollection(obj: JSONObject): List<GeoJSONFeatureState> {
        val features = obj.optJSONArray("features") ?: return emptyList()
        val result = mutableListOf<GeoJSONFeatureState>()
        for (i in 0 until features.length()) {
            val feature = features.optJSONObject(i) ?: continue
            parseFeature(feature)?.let { result.add(it) }
        }
        return result
    }

    private fun parseFeature(obj: JSONObject): GeoJSONFeatureState? {
        val geometryObj = obj.optJSONObject("geometry") ?: return null
        val geometry = parseGeometryObject(geometryObj) ?: return null

        val id =
            when {
                obj.has("id") -> obj.get("id").toString()
                else -> null
            }

        val properties = mutableMapOf<String, Any?>()
        obj.optJSONObject("properties")?.let { props ->
            for (key in props.keys()) {
                properties[key] = props.get(key).let { if (it == JSONObject.NULL) null else it }
            }
        }

        return GeoJSONFeatureState(
            featureId = id,
            geometry = geometry,
            properties = properties,
        )
    }

    internal fun parseGeometryObject(obj: JSONObject): GeoJSONGeometry? {
        return when (obj.optString("type")) {
            "Point" -> parsePoint(obj.optJSONArray("coordinates") ?: return null)
            "MultiPoint" -> parseMultiPoint(obj.optJSONArray("coordinates") ?: return null)
            "LineString" -> parseLineString(obj.optJSONArray("coordinates") ?: return null)
            "MultiLineString" -> parseMultiLineString(obj.optJSONArray("coordinates") ?: return null)
            "Polygon" -> parsePolygon(obj.optJSONArray("coordinates") ?: return null)
            "MultiPolygon" -> parseMultiPolygon(obj.optJSONArray("coordinates") ?: return null)
            "GeometryCollection" -> parseGeometryCollection(obj.optJSONArray("geometries") ?: return null)
            else -> null
        }
    }

    private fun parsePoint(coords: JSONArray): GeoJSONGeometry.Point? {
        if (coords.length() < 2) return null
        val lon = coords.optDouble(0)
        val lat = coords.optDouble(1)
        if (lon.isNaN() || lat.isNaN()) return null
        return GeoJSONGeometry.Point(longitude = lon, latitude = lat)
    }

    private fun parseMultiPoint(coords: JSONArray): GeoJSONGeometry.MultiPoint {
        val points = mutableListOf<GeoJSONGeometry.Point>()
        for (i in 0 until coords.length()) {
            parsePoint(coords.optJSONArray(i) ?: continue)?.let { points.add(it) }
        }
        return GeoJSONGeometry.MultiPoint(points)
    }

    private fun parseLineString(coords: JSONArray): GeoJSONGeometry.LineString =
        GeoJSONGeometry
            .LineString(parseLonLatList(coords))

    private fun parseMultiLineString(coords: JSONArray): GeoJSONGeometry.MultiLineString {
        val lines = mutableListOf<List<LonLat>>()
        for (i in 0 until coords.length()) {
            lines.add(parseLonLatList(coords.optJSONArray(i) ?: continue))
        }
        return GeoJSONGeometry.MultiLineString(lines)
    }

    private fun parsePolygon(coords: JSONArray): GeoJSONGeometry.Polygon {
        val rings = mutableListOf<List<LonLat>>()
        for (i in 0 until coords.length()) {
            rings.add(parseLonLatList(coords.optJSONArray(i) ?: continue))
        }
        return GeoJSONGeometry.Polygon(rings)
    }

    private fun parseMultiPolygon(coords: JSONArray): GeoJSONGeometry.MultiPolygon {
        val polygons = mutableListOf<List<List<LonLat>>>()
        for (i in 0 until coords.length()) {
            val polygonCoords = coords.optJSONArray(i) ?: continue
            val rings = mutableListOf<List<LonLat>>()
            for (j in 0 until polygonCoords.length()) {
                rings.add(parseLonLatList(polygonCoords.optJSONArray(j) ?: continue))
            }
            polygons.add(rings)
        }
        return GeoJSONGeometry.MultiPolygon(polygons)
    }

    private fun parseGeometryCollection(geometries: JSONArray): GeoJSONGeometry.GeometryCollection {
        val result = mutableListOf<GeoJSONGeometry>()
        for (i in 0 until geometries.length()) {
            parseGeometryObject(geometries.optJSONObject(i) ?: continue)?.let { result.add(it) }
        }
        return GeoJSONGeometry.GeometryCollection(result)
    }

    private fun parseLonLatList(coords: JSONArray): List<LonLat> {
        val result = mutableListOf<LonLat>()
        for (i in 0 until coords.length()) {
            val point = coords.optJSONArray(i) ?: continue
            if (point.length() < 2) continue
            val lon = point.optDouble(0)
            val lat = point.optDouble(1)
            if (!lon.isNaN() && !lat.isNaN()) {
                result.add(LonLat(longitude = lon, latitude = lat))
            }
        }
        return result
    }
}
