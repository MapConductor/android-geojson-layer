package com.mapconductor.geojson

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.LruCache
import com.mapconductor.core.tileserver.TileProviderInterface
import com.mapconductor.core.tileserver.TileRequest
import java.io.ByteArrayOutputStream
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import kotlin.math.PI
import kotlin.math.ln
import kotlin.math.sin

class GeoJSONTileRenderer(
    val tileSize: Int = GeoJSONDefaults.DEFAULT_TILE_SIZE,
    cacheSizeKb: Int = DEFAULT_CACHE_SIZE_KB,
    maxConcurrentRenders: Int = DEFAULT_MAX_CONCURRENT_RENDERS,
) : TileProviderInterface {
    private val cacheLock = Any()
    private val cache =
        object : LruCache<String, ByteArray>(cacheSizeKb) {
            override fun sizeOf(key: String, value: ByteArray): Int =
                (value.size / 1024).coerceAtLeast(1)
        }

    private val emptyTileMarker = ByteArray(1)
    private val inFlight = ConcurrentHashMap<String, CompletableFuture<ByteArray?>>()
    private val renderQueue = LinkedBlockingQueue<RenderJob>(MAX_QUEUE_SIZE)
    private val workerCount = maxConcurrentRenders.coerceIn(1, 4)

    @Volatile private var cacheEpoch = 0L
    @Volatile private var state = TileState(emptyList(), null)

    init {
        repeat(workerCount) { index ->
            Thread({ renderLoop() }, "GeoJSONTileRenderer-$index").apply {
                isDaemon = true
                start()
            }
        }
    }

    @JvmName("updateDynamic")
    fun update(features: List<GeoJSONFeatureState>, layerStyle: LayerStyle) {
        update(emptyList(), features, layerStyle)
    }

    @JvmName("updateStatic")
    fun update(staticFeatures: List<GeoJSONFeature>, layerStyle: LayerStyle) {
        update(staticFeatures, emptyList(), layerStyle)
    }

    fun update(
        staticFeatures: List<GeoJSONFeature>,
        dynamicFeatures: List<GeoJSONFeatureState>,
        layerStyle: LayerStyle,
    ) {
        val rendered = ArrayList<RenderFeature>(staticFeatures.size + dynamicFeatures.size)
        staticFeatures.forEach { if (it.visible) rendered.add(buildRenderFeature(it, layerStyle)) }
        dynamicFeatures.forEach { if (it.visible) rendered.add(buildRenderFeature(it, layerStyle)) }
        val index = if (rendered.size >= INDEX_THRESHOLD) buildIndex(rendered) else null
        state = TileState(rendered, index)
        synchronized(cacheLock) {
            cacheEpoch += 1
            cache.evictAll()
        }
    }

    override fun renderTile(request: TileRequest): ByteArray? {
        val epoch = cacheEpoch
        val key = "$epoch:${request.z}/${request.x}/${request.y}"
        synchronized(cacheLock) {
            cache.get(key)?.let { return if (it === emptyTileMarker) null else it }
        }

        val future = CompletableFuture<ByteArray?>()
        val existing = inFlight.putIfAbsent(key, future)
        if (existing != null) return existing.join()

        val job = RenderJob(key = key, epoch = epoch, request = request, state = state, future = future)
        try {
            renderQueue.put(job)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            inFlight.remove(key)
            future.complete(null)
            return null
        }
        return future.join()
    }

    private fun renderLoop() {
        while (true) {
            val job = try {
                renderQueue.take()
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
            try {
                synchronized(cacheLock) {
                    cache.get(job.key)?.let {
                        job.future.complete(if (it === emptyTileMarker) null else it)
                        return
                    }
                }
                val bytes = renderTileInternal(job.request, job.state)
                synchronized(cacheLock) {
                    if (cacheEpoch == job.epoch) {
                        cache.put(job.key, bytes ?: emptyTileMarker)
                    }
                }
                job.future.complete(bytes)
            } catch (e: Exception) {
                job.future.completeExceptionally(e)
            } finally {
                inFlight.remove(job.key)
            }
        }
    }

    private fun renderTileInternal(request: TileRequest, tileState: TileState): ByteArray? {
        if (tileState.features.isEmpty()) return null

        val z = request.z
        val worldTileCount = 1 shl z
        val x = ((request.x % worldTileCount) + worldTileCount) % worldTileCount
        val y = request.y
        if (y !in 0 until worldTileCount) return null

        val tileMinX = x.toDouble() / worldTileCount
        val tileMaxX = (x + 1).toDouble() / worldTileCount
        val tileMinY = y.toDouble() / worldTileCount
        val tileMaxY = (y + 1).toDouble() / worldTileCount

        val candidates = tileState.index?.query(tileMinX, tileMinY, tileMaxX, tileMaxY)
            ?: tileState.features.indices.toList()

        var hasContent = false
        val bitmap = getBitmap()
        bitmap.eraseColor(android.graphics.Color.TRANSPARENT)
        val canvas = Canvas(bitmap)

        val worldSize = tileSize.toDouble() * worldTileCount
        val originX = x.toDouble() * tileSize
        val originY = y.toDouble() * tileSize

        fun toPixelX(wx: Double): Float = ((wx * worldSize) - originX).toFloat()
        fun toPixelY(wy: Double): Float = ((wy * worldSize) - originY).toFloat()

        for (idx in candidates) {
            val feature = tileState.features[idx]
            if (!feature.bounds.intersects(tileMinX, tileMinY, tileMaxX, tileMaxY)) continue
            if (renderFeature(canvas, feature, ::toPixelX, ::toPixelY)) {
                hasContent = true
            }
        }

        if (!hasContent) return null
        return bitmapToPng(bitmap)
    }

    private fun renderFeature(
        canvas: Canvas,
        feature: RenderFeature,
        toPixelX: (Double) -> Float,
        toPixelY: (Double) -> Float,
    ): Boolean = renderGeometry(canvas, feature, feature.worldGeometry, toPixelX, toPixelY)

    private fun renderGeometry(
        canvas: Canvas,
        feature: RenderFeature,
        geometry: WorldGeometry,
        toPixelX: (Double) -> Float,
        toPixelY: (Double) -> Float,
    ): Boolean {
        return when (geometry) {
            is WorldGeometry.Point -> {
                val px = toPixelX(geometry.wx)
                val py = toPixelY(geometry.wy)
                canvas.drawCircle(px, py, feature.pointRadius, feature.fillPaint)
                feature.strokePaint?.let { canvas.drawCircle(px, py, feature.pointRadius, it) }
                true
            }

            is WorldGeometry.Points -> {
                for (pt in geometry.pts) {
                    val px = toPixelX(pt.wx)
                    val py = toPixelY(pt.wy)
                    canvas.drawCircle(px, py, feature.pointRadius, feature.fillPaint)
                    feature.strokePaint?.let { canvas.drawCircle(px, py, feature.pointRadius, it) }
                }
                geometry.pts.isNotEmpty()
            }

            is WorldGeometry.Line -> {
                val path = buildLinePath(geometry.rings, toPixelX, toPixelY)
                if (!path.isEmpty) {
                    canvas.drawPath(path, feature.strokePaint ?: feature.fillPaint)
                    true
                } else {
                    false
                }
            }

            is WorldGeometry.Polygon -> {
                val path = buildPolygonPath(geometry.rings, toPixelX, toPixelY)
                if (!path.isEmpty) {
                    canvas.drawPath(path, feature.fillPaint)
                    feature.strokePaint?.let { canvas.drawPath(path, it) }
                    true
                } else {
                    false
                }
            }

            is WorldGeometry.Collection -> {
                var drew = false
                for (part in geometry.parts) {
                    if (renderGeometry(canvas, feature, part, toPixelX, toPixelY)) drew = true
                }
                drew
            }

            WorldGeometry.Empty -> false
        }
    }

    private fun buildLinePath(
        rings: List<List<WorldPoint>>,
        toPixelX: (Double) -> Float,
        toPixelY: (Double) -> Float,
    ): Path {
        val path = Path()
        for (ring in rings) {
            if (ring.size < 2) continue
            path.moveTo(toPixelX(ring[0].wx), toPixelY(ring[0].wy))
            for (i in 1 until ring.size) {
                path.lineTo(toPixelX(ring[i].wx), toPixelY(ring[i].wy))
            }
        }
        return path
    }

    private fun buildPolygonPath(
        rings: List<List<WorldPoint>>,
        toPixelX: (Double) -> Float,
        toPixelY: (Double) -> Float,
    ): Path {
        val path = Path().apply { fillType = Path.FillType.EVEN_ODD }
        for (ring in rings) {
            if (ring.size < 3) continue
            path.moveTo(toPixelX(ring[0].wx), toPixelY(ring[0].wy))
            for (i in 1 until ring.size) {
                path.lineTo(toPixelX(ring[i].wx), toPixelY(ring[i].wy))
            }
            path.close()
        }
        return path
    }

    private fun lonToWorld(lon: Double): Double = lon / 360.0 + 0.5

    private fun latToWorld(lat: Double): Double {
        val siny = sin(lat * PI / 180.0).coerceIn(-0.9999, 0.9999)
        return 0.5 - ln((1.0 + siny) / (1.0 - siny)) / (4.0 * PI)
    }

    private fun buildRenderFeature(feature: GeoJSONFeature, layerStyle: LayerStyle): RenderFeature {
        val strokeColor = feature.strokeColor ?: layerStyle.strokeColor
        val fillColor = feature.fillColor ?: layerStyle.fillColor
        val strokeWidth = feature.strokeWidth ?: layerStyle.strokeWidth
        val pointRadius = feature.pointRadius ?: layerStyle.pointRadius
        return buildRenderFeatureFromStyle(feature, feature.geometry, strokeColor, fillColor, strokeWidth, pointRadius)
    }

    private fun buildRenderFeature(state: GeoJSONFeatureState, layerStyle: LayerStyle): RenderFeature {
        val source = GeoJSONFeature(
            id = state.id,
            geometry = state.geometry,
            properties = state.properties,
            strokeColor = state.strokeColor,
            fillColor = state.fillColor,
            strokeWidth = state.strokeWidth,
            pointRadius = state.pointRadius,
            visible = state.visible,
        )
        val strokeColor = state.strokeColor ?: layerStyle.strokeColor
        val fillColor = state.fillColor ?: layerStyle.fillColor
        val strokeWidth = state.strokeWidth ?: layerStyle.strokeWidth
        val pointRadius = state.pointRadius ?: layerStyle.pointRadius
        return buildRenderFeatureFromStyle(source, source.geometry, strokeColor, fillColor, strokeWidth, pointRadius)
    }

    private fun buildRenderFeatureFromStyle(
        source: GeoJSONFeature,
        geometry: GeoJSONGeometry,
        strokeColor: Int,
        fillColor: Int,
        strokeWidth: Float,
        pointRadius: Float,
    ): RenderFeature {

        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = fillColor
        }
        val strokePaint = if (android.graphics.Color.alpha(strokeColor) > 0 && strokeWidth > 0f) {
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                color = strokeColor
                this.strokeWidth = strokeWidth
                strokeJoin = Paint.Join.ROUND
                strokeCap = Paint.Cap.ROUND
            }
        } else {
            null
        }

        val worldGeometry = toWorldGeometry(geometry)
        val bounds = computeBounds(worldGeometry)
        // Strip the geometry from source: worldGeometry already holds all coordinates in world
        // space for rendering and hit-testing. Keeping lat/lon coords here doubles memory usage,
        // which causes OOM on large datasets with multiple renderers.
        return RenderFeature(
            source = source.copy(geometry = GeoJSONGeometry.Empty),
            worldGeometry = worldGeometry,
            bounds = bounds,
            fillPaint = fillPaint,
            strokePaint = strokePaint,
            pointRadius = pointRadius,
        )
    }

    private fun toWorldGeometry(geometry: GeoJSONGeometry): WorldGeometry =
        when (geometry) {
            is GeoJSONGeometry.Point -> WorldGeometry.Point(
                wx = lonToWorld(geometry.longitude),
                wy = latToWorld(geometry.latitude),
            )

            is GeoJSONGeometry.MultiPoint -> WorldGeometry.Points(
                pts = geometry.points.map { pt ->
                    WorldPoint(lonToWorld(pt.longitude), latToWorld(pt.latitude))
                },
            )

            is GeoJSONGeometry.LineString -> WorldGeometry.Line(
                rings = listOf(geometry.coordinates.map {
                    WorldPoint(lonToWorld(it.longitude), latToWorld(it.latitude))
                }),
            )

            is GeoJSONGeometry.MultiLineString -> WorldGeometry.Line(
                rings = geometry.lines.map { line ->
                    line.map { WorldPoint(lonToWorld(it.longitude), latToWorld(it.latitude)) }
                },
            )

            is GeoJSONGeometry.Polygon -> WorldGeometry.Polygon(
                rings = geometry.rings.map { ring ->
                    ring.map { WorldPoint(lonToWorld(it.longitude), latToWorld(it.latitude)) }
                },
            )

            is GeoJSONGeometry.MultiPolygon -> WorldGeometry.Collection(
                parts = geometry.polygons.map { poly ->
                    WorldGeometry.Polygon(
                        rings = poly.map { ring ->
                            ring.map { WorldPoint(lonToWorld(it.longitude), latToWorld(it.latitude)) }
                        },
                    )
                },
            )

            is GeoJSONGeometry.GeometryCollection -> WorldGeometry.Collection(
                parts = geometry.geometries.map { toWorldGeometry(it) },
            )

            GeoJSONGeometry.Empty -> WorldGeometry.Empty
        }

    private fun computeBounds(geometry: WorldGeometry): WorldBounds =
        when (geometry) {
            is WorldGeometry.Point -> WorldBounds(geometry.wx, geometry.wx, geometry.wy, geometry.wy)
            is WorldGeometry.Points -> boundsOfPoints(geometry.pts)
            is WorldGeometry.Line -> boundsOfRings(geometry.rings)
            is WorldGeometry.Polygon -> boundsOfRings(geometry.rings)
            is WorldGeometry.Collection -> {
                val childBounds = geometry.parts.map { computeBounds(it) }
                WorldBounds(
                    minX = childBounds.minOf { it.minX },
                    maxX = childBounds.maxOf { it.maxX },
                    minY = childBounds.minOf { it.minY },
                    maxY = childBounds.maxOf { it.maxY },
                )
            }
            WorldGeometry.Empty -> WorldBounds(0.0, 1.0, 0.0, 1.0)
        }

    private fun boundsOfPoints(pts: List<WorldPoint>): WorldBounds {
        if (pts.isEmpty()) return WorldBounds(0.0, 1.0, 0.0, 1.0)
        var minX = pts[0].wx; var maxX = pts[0].wx
        var minY = pts[0].wy; var maxY = pts[0].wy
        for (pt in pts) {
            if (pt.wx < minX) minX = pt.wx; if (pt.wx > maxX) maxX = pt.wx
            if (pt.wy < minY) minY = pt.wy; if (pt.wy > maxY) maxY = pt.wy
        }
        return WorldBounds(minX, maxX, minY, maxY)
    }

    private fun boundsOfRings(rings: List<List<WorldPoint>>): WorldBounds {
        var minX = Double.MAX_VALUE; var maxX = -Double.MAX_VALUE
        var minY = Double.MAX_VALUE; var maxY = -Double.MAX_VALUE
        for (ring in rings) {
            for (pt in ring) {
                if (pt.wx < minX) minX = pt.wx; if (pt.wx > maxX) maxX = pt.wx
                if (pt.wy < minY) minY = pt.wy; if (pt.wy > maxY) maxY = pt.wy
            }
        }
        return if (minX <= maxX) WorldBounds(minX, maxX, minY, maxY)
        else WorldBounds(0.0, 1.0, 0.0, 1.0)
    }

    private fun buildIndex(features: List<RenderFeature>): SpatialIndex {
        val grid = Array(INDEX_GRID_SIZE * INDEX_GRID_SIZE) { mutableListOf<Int>() }
        for (i in features.indices) {
            val b = features[i].bounds
            val x0 = (b.minX * INDEX_GRID_SIZE).toInt().coerceIn(0, INDEX_GRID_SIZE - 1)
            val x1 = (b.maxX * INDEX_GRID_SIZE).toInt().coerceIn(0, INDEX_GRID_SIZE - 1)
            val y0 = (b.minY * INDEX_GRID_SIZE).toInt().coerceIn(0, INDEX_GRID_SIZE - 1)
            val y1 = (b.maxY * INDEX_GRID_SIZE).toInt().coerceIn(0, INDEX_GRID_SIZE - 1)
            for (cy in y0..y1) {
                for (cx in x0..x1) {
                    grid[cy * INDEX_GRID_SIZE + cx].add(i)
                }
            }
        }
        return SpatialIndex(grid, features.size)
    }

    private fun bitmapToPng(bitmap: Bitmap): ByteArray {
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        return out.toByteArray()
    }

    private val threadLocalBitmap = ThreadLocal<Bitmap>()

    private fun getBitmap(): Bitmap {
        val existing = threadLocalBitmap.get()
        if (existing != null && !existing.isRecycled &&
            existing.width == tileSize && existing.height == tileSize
        ) {
            return existing
        }
        val bm = Bitmap.createBitmap(tileSize, tileSize, Bitmap.Config.ARGB_8888)
        threadLocalBitmap.set(bm)
        return bm
    }

    data class LayerStyle(
        val strokeColor: Int,
        val fillColor: Int,
        val strokeWidth: Float,
        val pointRadius: Float,
    )

    private data class WorldPoint(val wx: Double, val wy: Double)

    private data class WorldBounds(
        val minX: Double, val maxX: Double,
        val minY: Double, val maxY: Double,
    ) {
        fun intersects(x1: Double, y1: Double, x2: Double, y2: Double): Boolean =
            minX <= x2 && maxX >= x1 && minY <= y2 && maxY >= y1
    }

    private sealed class WorldGeometry {
        data class Point(val wx: Double, val wy: Double) : WorldGeometry()
        data class Points(val pts: List<WorldPoint>) : WorldGeometry()
        data class Line(val rings: List<List<WorldPoint>>) : WorldGeometry()
        data class Polygon(val rings: List<List<WorldPoint>>) : WorldGeometry()
        data class Collection(val parts: List<WorldGeometry>) : WorldGeometry()
        object Empty : WorldGeometry()
    }

    private data class RenderFeature(
        val source: GeoJSONFeature,
        val worldGeometry: WorldGeometry,
        val bounds: WorldBounds,
        val fillPaint: Paint,
        val strokePaint: Paint?,
        val pointRadius: Float,
    )

    private class SpatialIndex(
        private val grid: Array<MutableList<Int>>,
        private val featureCount: Int,
    ) {
        fun query(x1: Double, y1: Double, x2: Double, y2: Double): List<Int> {
            val cx0 = (x1 * INDEX_GRID_SIZE).toInt().coerceIn(0, INDEX_GRID_SIZE - 1)
            val cx1 = (x2 * INDEX_GRID_SIZE).toInt().coerceIn(0, INDEX_GRID_SIZE - 1)
            val cy0 = (y1 * INDEX_GRID_SIZE).toInt().coerceIn(0, INDEX_GRID_SIZE - 1)
            val cy1 = (y2 * INDEX_GRID_SIZE).toInt().coerceIn(0, INDEX_GRID_SIZE - 1)
            // BitSet uses 1 bit per feature instead of ~32 bytes per entry in HashSet,
            // which prevents OOM when many features fall into the same tile.
            val seen = java.util.BitSet(featureCount)
            val result = ArrayList<Int>()
            for (cy in cy0..cy1) {
                for (cx in cx0..cx1) {
                    for (idx in grid[cy * INDEX_GRID_SIZE + cx]) {
                        if (!seen.get(idx)) {
                            seen.set(idx)
                            result.add(idx)
                        }
                    }
                }
            }
            return result
        }
    }

    private data class TileState(
        val features: List<RenderFeature>,
        val index: SpatialIndex?,
    )

    private data class RenderJob(
        val key: String,
        val epoch: Long,
        val request: TileRequest,
        val state: TileState,
        val future: CompletableFuture<ByteArray?>,
    )

    // ── Hit-testing ──────────────────────────────────────────────────────────

    /**
     * Returns the topmost [GeoJSONFeature] whose geometry contains or is near
     * [longitude]/[latitude], or null if nothing is hit.
     * Call this from a map-level onClick handler on the main thread.
     */
    fun hitTest(longitude: Double, latitude: Double): GeoJSONFeature? {
        val wx = lonToWorld(longitude)
        val wy = latToWorld(latitude)
        val currentState = state
        val tolerance = HIT_LINE_TOLERANCE
        val candidates = currentState.index
            ?.query(wx - tolerance, wy - tolerance, wx + tolerance, wy + tolerance)
            ?: currentState.features.indices.toList()

        for (idx in candidates.asReversed()) {
            val feature = currentState.features[idx]
            if (!feature.bounds.intersects(wx - tolerance, wy - tolerance, wx + tolerance, wy + tolerance)) continue
            if (hitTestGeometry(wx, wy, feature.worldGeometry)) return feature.source
        }
        return null
    }

    private fun hitTestGeometry(wx: Double, wy: Double, geometry: WorldGeometry): Boolean =
        when (geometry) {
            is WorldGeometry.Point ->
                distanceSq(wx, wy, geometry.wx, geometry.wy) <= HIT_POINT_TOLERANCE_SQ

            is WorldGeometry.Points ->
                geometry.pts.any { distanceSq(wx, wy, it.wx, it.wy) <= HIT_POINT_TOLERANCE_SQ }

            is WorldGeometry.Line ->
                geometry.rings.any { ring ->
                    ring.zipWithNext().any { (a, b) ->
                        segmentDistanceSq(wx, wy, a.wx, a.wy, b.wx, b.wy) <= HIT_LINE_TOLERANCE_SQ
                    }
                }

            is WorldGeometry.Polygon -> {
                val rings = geometry.rings
                rings.isNotEmpty() &&
                    pointInRing(wx, wy, rings[0]) &&
                    rings.drop(1).none { hole -> pointInRing(wx, wy, hole) }
            }

            is WorldGeometry.Collection ->
                geometry.parts.any { hitTestGeometry(wx, wy, it) }

            WorldGeometry.Empty -> false
        }

    private fun pointInRing(wx: Double, wy: Double, ring: List<WorldPoint>): Boolean {
        var inside = false
        var j = ring.size - 1
        for (i in ring.indices) {
            val xi = ring[i].wx; val yi = ring[i].wy
            val xj = ring[j].wx; val yj = ring[j].wy
            if (((yi > wy) != (yj > wy)) && (wx < (xj - xi) * (wy - yi) / (yj - yi) + xi)) {
                inside = !inside
            }
            j = i
        }
        return inside
    }

    private fun segmentDistanceSq(
        px: Double, py: Double,
        ax: Double, ay: Double,
        bx: Double, by: Double,
    ): Double {
        val dx = bx - ax; val dy = by - ay
        if (dx == 0.0 && dy == 0.0) return distanceSq(px, py, ax, ay)
        val t = ((px - ax) * dx + (py - ay) * dy) / (dx * dx + dy * dy)
        val tc = t.coerceIn(0.0, 1.0)
        return distanceSq(px, py, ax + tc * dx, ay + tc * dy)
    }

    private fun distanceSq(ax: Double, ay: Double, bx: Double, by: Double): Double {
        val dx = ax - bx; val dy = ay - by
        return dx * dx + dy * dy
    }

    companion object {
        private const val DEFAULT_CACHE_SIZE_KB = 8 * 1024
        private const val DEFAULT_MAX_CONCURRENT_RENDERS = 2
        private const val MAX_QUEUE_SIZE = 512
        private const val INDEX_THRESHOLD = 256
        private const val INDEX_GRID_SIZE = 64

        // World-coordinate hit tolerances (~0.0002 ≈ 72m at equator, ~3-5px at zoom 14)
        private const val HIT_LINE_TOLERANCE = 0.0002
        private const val HIT_LINE_TOLERANCE_SQ = HIT_LINE_TOLERANCE * HIT_LINE_TOLERANCE
        private const val HIT_POINT_TOLERANCE = 0.0004
        private const val HIT_POINT_TOLERANCE_SQ = HIT_POINT_TOLERANCE * HIT_POINT_TOLERANCE
    }
}
