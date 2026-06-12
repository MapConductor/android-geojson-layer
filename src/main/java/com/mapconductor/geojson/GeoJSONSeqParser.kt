package com.mapconductor.geojson

import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader

/**
 * Parser for GeoJSON Text Sequences (RFC 8142).
 *
 * Records are separated by RS (0x1E). Each record is a GeoJSON Feature or bare geometry.
 * Unlike FeatureCollection, there is no wrapping object, making this format
 * suitable for streaming very large datasets.
 */
object GeoJSONSeqParser {

    private const val RS = '\u001E'
    private const val CHUNK_SIZE = 8192

    fun parse(inputStream: InputStream): List<GeoJSONFeature> {
        val result = mutableListOf<GeoJSONFeature>()
        streamParse(inputStream) { result.add(it) }
        return result
    }

    fun parse(file: File): List<GeoJSONFeature> = parse(file.inputStream())

    fun streamParse(inputStream: InputStream, onFeature: (GeoJSONFeature) -> Unit) {
        val reader = InputStreamReader(inputStream, Charsets.UTF_8).buffered()
        val chunk = CharArray(CHUNK_SIZE)
        val record = StringBuilder()
        reader.use {
            var n: Int
            while (reader.read(chunk).also { n = it } != -1) {
                for (i in 0 until n) {
                    if (chunk[i] == RS) {
                        flushRecord(record, onFeature)
                    } else {
                        record.append(chunk[i])
                    }
                }
            }
            flushRecord(record, onFeature)
        }
    }

    fun streamParse(file: File, onFeature: (GeoJSONFeature) -> Unit) =
        streamParse(file.inputStream(), onFeature)

    private fun flushRecord(record: StringBuilder, onFeature: (GeoJSONFeature) -> Unit) {
        val text = record.toString().trim()
        record.clear()
        if (text.isEmpty()) return
        parseRecord(text)?.let(onFeature)
    }

    private fun parseRecord(text: String): GeoJSONFeature? {
        val obj = runCatching { JSONObject(text) }.getOrNull() ?: return null
        return when (obj.optString("type")) {
            "Feature" -> GeoJSONParser.parseFeatureAsData(obj)
            else -> {
                val geometry = GeoJSONParser.parseGeometryObject(obj) ?: return null
                GeoJSONFeature(geometry = geometry)
            }
        }
    }
}
