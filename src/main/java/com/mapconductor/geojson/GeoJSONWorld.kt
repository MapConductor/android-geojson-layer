package com.mapconductor.geojson

import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sinh

/** 世界座標（0..1 の正規化 Web メルカトル）での矩形。 */
internal data class WorldBounds(
    val minX: Double,
    val maxX: Double,
    val minY: Double,
    val maxY: Double,
) {
    fun intersects(
        x1: Double,
        y1: Double,
        x2: Double,
        y2: Double,
    ): Boolean = minX <= x2 && maxX >= x1 && minY <= y2 && maxY >= y1
}

/**
 * 世界座標へ落としたあとのジオメトリ。
 *
 * 緯度経度のままではなく先に世界座標へ移しておくのは、タイルを描くたびに
 * 投影を計算し直さないため。数万点のデータでは投影が支配的になる。
 */
internal sealed class WorldGeometry {
    data class Point(
        val wx: Double,
        val wy: Double,
    ) : WorldGeometry()

    data class Points(
        val points: DoubleArray,
    ) : WorldGeometry()

    data class Line(
        val rings: List<WorldRing>,
    ) : WorldGeometry()

    data class Polygon(
        val rings: List<WorldRing>,
    ) : WorldGeometry()

    data class Collection(
        val parts: List<WorldGeometry>,
    ) : WorldGeometry()

    object Empty : WorldGeometry()
}

/**
 * 線・多角形の 1 連なり。座標は `[x0, y0, x1, y1, ...]` の平坦な配列で持つ。
 *
 * **ズームごとに間引いた結果をキャッシュする。** 引いた状態では 1px 未満の
 * 頂点は見えないので、そのまま描くと無駄な `lineTo` が大量に走る。
 * タイルは並行に描かれるため、キャッシュは `AtomicReferenceArray` で持ち、
 * 競合したら先に入った方を使う（どちらも同じ結果なので捨てて構わない）。
 */
internal class WorldRing(
    val coords: DoubleArray,
) {
    private val simplifiedByZoom =
        java.util.concurrent.atomic
            .AtomicReferenceArray<DoubleArray>((GeoJSONWorld.MAX_SIMPLIFY_ZOOM + 1) * GeoJSONWorld.MAX_PIXEL_RATIO)

    fun coordsForZoom(
        zoom: Int,
        tileSize: Int,
        pixelRatio: Int,
    ): DoubleArray {
        if (coords.size < 6) return coords
        val normalizedZoom = zoom.coerceIn(0, GeoJSONWorld.MAX_SIMPLIFY_ZOOM)
        val normalizedPixelRatio = pixelRatio.coerceIn(1, GeoJSONWorld.MAX_PIXEL_RATIO)
        val cacheIndex = normalizedZoom * GeoJSONWorld.MAX_PIXEL_RATIO + normalizedPixelRatio - 1
        simplifiedByZoom.get(cacheIndex)?.let { return it }
        val tolerance = 0.5 / (tileSize.toDouble() * normalizedPixelRatio * (1 shl normalizedZoom))
        val simplified = GeoJSONWorld.simplifyRadial(coords, tolerance)
        return if (simplifiedByZoom.compareAndSet(cacheIndex, null, simplified)) {
            simplified
        } else {
            simplifiedByZoom.get(cacheIndex)
        }
    }
}

/**
 * 緯度経度と世界座標の相互変換、ジオメトリの世界座標化、範囲の計算、間引き。
 *
 * すべて副作用のない計算で、描画にもキャッシュにも触らない。
 *
 * ios-sdk / react-sdk の同名ファイルと同じ式。片方だけ直すと 3 者の
 * 描画結果や当たり判定がずれるので、変えるときは 3 つとも直すこと。
 */
internal object GeoJSONWorld {
    const val MAX_SIMPLIFY_ZOOM = 22
    const val MAX_PIXEL_RATIO = 3
    private const val MAX_ABS_SIN_LAT = 0.9999

    fun lonToWorld(lon: Double): Double = lon / 360.0 + 0.5

    fun worldToLon(wx: Double): Double = (wx - 0.5) * 360.0

    fun latToWorld(lat: Double): Double {
        val siny = sin(lat * PI / 180.0).coerceIn(-MAX_ABS_SIN_LAT, MAX_ABS_SIN_LAT)
        return 0.5 - ln((1.0 + siny) / (1.0 - siny)) / (4.0 * PI)
    }

    fun worldToLat(wy: Double): Double = atan(sinh(PI * (1.0 - 2.0 * wy))) * 180.0 / PI

    fun toPixel(
        world: Double,
        worldSize: Double,
        origin: Double,
    ): Float = ((world * worldSize) - origin).toFloat()

    /** 線分が矩形の外側に完全に出ているか。描く前の足切りに使う。 */
    fun segmentOutside(
        ax: Double,
        ay: Double,
        bx: Double,
        by: Double,
        minX: Double,
        minY: Double,
        maxX: Double,
        maxY: Double,
    ): Boolean =
        (ax < minX && bx < minX) ||
            (ax > maxX && bx > maxX) ||
            (ay < minY && by < minY) ||
            (ay > maxY && by > maxY)

    fun toWorldGeometry(geometry: GeoJSONGeometry): WorldGeometry =
        when (geometry) {
            is GeoJSONGeometry.Point ->
                WorldGeometry.Point(
                    wx = lonToWorld(geometry.longitude),
                    wy = latToWorld(geometry.latitude),
                )

            is GeoJSONGeometry.MultiPoint -> WorldGeometry.Points(points = flatPoints(geometry.points))

            is GeoJSONGeometry.LineString ->
                WorldGeometry.Line(rings = listOf(WorldRing(flatCoordinates(geometry.coordinates))))

            is GeoJSONGeometry.MultiLineString ->
                WorldGeometry.Line(rings = geometry.lines.map { WorldRing(flatCoordinates(it)) })

            is GeoJSONGeometry.Polygon ->
                WorldGeometry.Polygon(rings = geometry.rings.map { WorldRing(flatCoordinates(it)) })

            is GeoJSONGeometry.MultiPolygon ->
                WorldGeometry.Collection(
                    parts =
                        geometry.polygons.map { poly ->
                            WorldGeometry.Polygon(rings = poly.map { WorldRing(flatCoordinates(it)) })
                        },
                )

            is GeoJSONGeometry.GeometryCollection ->
                WorldGeometry.Collection(parts = geometry.geometries.map { toWorldGeometry(it) })

            GeoJSONGeometry.Empty -> WorldGeometry.Empty
        }

    private fun flatPoints(points: List<GeoJSONGeometry.Point>): DoubleArray {
        val coords = DoubleArray(points.size * 2)
        var i = 0
        for (point in points) {
            coords[i++] = lonToWorld(point.longitude)
            coords[i++] = latToWorld(point.latitude)
        }
        return coords
    }

    private fun flatCoordinates(points: List<LonLat>): DoubleArray {
        val coords = DoubleArray(points.size * 2)
        var i = 0
        for (point in points) {
            coords[i++] = lonToWorld(point.longitude)
            coords[i++] = latToWorld(point.latitude)
        }
        return coords
    }

    fun computeBounds(geometry: WorldGeometry): WorldBounds =
        when (geometry) {
            is WorldGeometry.Point -> WorldBounds(geometry.wx, geometry.wx, geometry.wy, geometry.wy)
            is WorldGeometry.Points -> boundsOfCoords(geometry.points)
            is WorldGeometry.Line -> boundsOfRings(geometry.rings)
            is WorldGeometry.Polygon -> boundsOfRings(geometry.rings)
            is WorldGeometry.Collection -> {
                if (geometry.parts.isEmpty()) {
                    WorldBounds(0.0, 1.0, 0.0, 1.0)
                } else {
                    val childBounds = geometry.parts.map { computeBounds(it) }
                    WorldBounds(
                        minX = childBounds.minOf { it.minX },
                        maxX = childBounds.maxOf { it.maxX },
                        minY = childBounds.minOf { it.minY },
                        maxY = childBounds.maxOf { it.maxY },
                    )
                }
            }
            WorldGeometry.Empty -> WorldBounds(0.0, 1.0, 0.0, 1.0)
        }

    private fun boundsOfCoords(coords: DoubleArray): WorldBounds {
        if (coords.isEmpty()) return WorldBounds(0.0, 1.0, 0.0, 1.0)
        var minX = coords[0]
        var maxX = coords[0]
        var minY = coords[1]
        var maxY = coords[1]
        var i = 2
        while (i < coords.size) {
            val x = coords[i]
            val y = coords[i + 1]
            if (x < minX) minX = x
            if (x > maxX) maxX = x
            if (y < minY) minY = y
            if (y > maxY) maxY = y
            i += 2
        }
        return WorldBounds(minX, maxX, minY, maxY)
    }

    private fun boundsOfRings(rings: List<WorldRing>): WorldBounds {
        var minX = Double.MAX_VALUE
        var maxX = -Double.MAX_VALUE
        var minY = Double.MAX_VALUE
        var maxY = -Double.MAX_VALUE
        for (ring in rings) {
            val coords = ring.coords
            var i = 0
            while (i < coords.size) {
                val x = coords[i]
                val y = coords[i + 1]
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y
                i += 2
            }
        }
        return if (minX <= maxX) {
            WorldBounds(minX, maxX, minY, maxY)
        } else {
            WorldBounds(0.0, 1.0, 0.0, 1.0)
        }
    }

    fun distanceSq(
        ax: Double,
        ay: Double,
        bx: Double,
        by: Double,
    ): Double {
        val dx = ax - bx
        val dy = ay - by
        return dx * dx + dy * dy
    }

    /**
     * 直前に残した頂点から [tolerance] 以内の点を落とす（radial distance 簡略化）。
     *
     * Douglas-Peucker より粗いが 1 パスで済む。タイルごとに呼ばれるため、
     * ここでの計算量がそのまま描画の待ち時間になる。始点と終点は必ず残す。
     */
    fun simplifyRadial(
        coords: DoubleArray,
        tolerance: Double,
    ): DoubleArray {
        if (coords.size <= 4 || tolerance <= 0.0) return coords
        val toleranceSq = tolerance * tolerance
        val output = DoubleArray(coords.size)
        var out = 0
        var lastX = coords[0]
        var lastY = coords[1]
        output[out++] = lastX
        output[out++] = lastY

        var i = 2
        while (i < coords.size - 2) {
            val x = coords[i]
            val y = coords[i + 1]
            if (distanceSq(lastX, lastY, x, y) > toleranceSq) {
                output[out++] = x
                output[out++] = y
                lastX = x
                lastY = y
            }
            i += 2
        }

        val endX = coords[coords.size - 2]
        val endY = coords[coords.size - 1]
        if (out < 2 || output[out - 2] != endX || output[out - 1] != endY) {
            output[out++] = endX
            output[out++] = endY
        }
        return if (out == coords.size) coords else output.copyOf(out)
    }
}
