package com.mapconductor.geojson

import com.mapconductor.core.features.GeoPoint
import com.mapconductor.core.tileserver.TileProviderInterface
import com.mapconductor.core.tileserver.TileRequest
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import android.graphics.Canvas
import android.util.LruCache

/**
 * GeoJSON をタイルへ描くタイルプロバイダ。
 *
 * このクラスが持つのは**元データの保持とタイル要求の段取り**だけで、
 * 実際の計算は責務ごとのファイルにある:
 *
 * | 部品                            | 担当                                     |
 * |---------------------------------|------------------------------------------|
 * | [GeoJSONWorld]                  | 緯度経度→世界座標、範囲、間引き          |
 * | [GeoJSONRenderFeatureBuilder]   | スタイル解決と描画用フィーチャーの組み立て|
 * | [GeoJSONSpatialIndex]           | タイルにかかるフィーチャーの絞り込み     |
 * | [GeoJSONTilePainter]            | Canvas への描画と PNG 化                 |
 * | [GeoJSONHitTester]              | クリック位置の当たり判定                 |
 *
 * ios-sdk / react-sdk も同じ責務分けのファイル構成にしてある。
 */
class GeoJSONTileRenderer(
    val tileSize: Int = GeoJSONDefaults.DEFAULT_TILE_SIZE,
    cacheSizeKb: Int = DEFAULT_CACHE_SIZE_KB,
    maxConcurrentRenders: Int = DEFAULT_MAX_CONCURRENT_RENDERS,
) : TileProviderInterface {
    private val cacheLock = Any()
    private val cache =
        object : LruCache<String, ByteArray>(cacheSizeKb) {
            override fun sizeOf(
                key: String,
                value: ByteArray,
            ): Int = (value.size / 1024).coerceAtLeast(1)
        }

    // 空タイルを実体ではなくこの目印で覚える。返すときに null へ戻す。
    private val emptyTileMarker = ByteArray(1)
    private val inFlight = ConcurrentHashMap<String, CompletableFuture<ByteArray?>>()
    private val renderQueue = LinkedBlockingQueue<RenderJob>(MAX_QUEUE_SIZE)
    private val workerCount = maxConcurrentRenders.coerceIn(1, MAX_CONCURRENT_RENDERS)

    private val painter = GeoJSONTilePainter(tileSize)
    private val hitTester = GeoJSONHitTester()

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
    fun update(
        features: List<GeoJSONFeatureState>,
        layerStyle: LayerStyle,
        styleProvider: GeoJSONStyleProviderInterface = DefaultGeoJSONStyleProvider,
    ) {
        update(emptyList(), features, layerStyle, styleProvider)
    }

    @JvmName("updateStatic")
    fun update(
        staticFeatures: List<GeoJSONFeature>,
        layerStyle: LayerStyle,
        styleProvider: GeoJSONStyleProviderInterface = DefaultGeoJSONStyleProvider,
    ) {
        update(staticFeatures, emptyList(), layerStyle, styleProvider)
    }

    fun update(
        staticFeatures: List<GeoJSONFeature>,
        dynamicFeatures: List<GeoJSONFeatureState>,
        layerStyle: LayerStyle,
        styleProvider: GeoJSONStyleProviderInterface = DefaultGeoJSONStyleProvider,
    ) {
        val rendered = ArrayList<RenderFeature>(staticFeatures.size + dynamicFeatures.size)
        staticFeatures.forEach {
            if (it.visible) rendered.add(GeoJSONRenderFeatureBuilder.build(it, layerStyle, styleProvider))
        }
        dynamicFeatures.forEach {
            if (it.visible) rendered.add(GeoJSONRenderFeatureBuilder.build(it, layerStyle, styleProvider))
        }
        val index =
            if (rendered.size >= GeoJSONSpatialIndex.BUILD_THRESHOLD) GeoJSONSpatialIndex.build(rendered) else null
        state = TileState(rendered, index)
        synchronized(cacheLock) {
            cacheEpoch += 1
            cache.evictAll()
        }
    }

    override fun renderTile(request: TileRequest): ByteArray? {
        val epoch = cacheEpoch
        val pixelRatio = request.pixelRatio.coerceIn(1, GeoJSONWorld.MAX_PIXEL_RATIO)
        val normalizedRequest = request.copy(pixelRatio = pixelRatio)
        val key = "$epoch:${pixelRatio}x:${request.z}/${request.x}/${request.y}"
        synchronized(cacheLock) {
            cache.get(key)?.let { return if (it === emptyTileMarker) null else it }
        }

        // 同じタイルが同時に要求されたら 1 回だけ描いて全員で待つ。
        val future = CompletableFuture<ByteArray?>()
        val existing = inFlight.putIfAbsent(key, future)
        if (existing != null) return existing.join()

        val job = RenderJob(key = key, epoch = epoch, request = normalizedRequest, state = state, future = future)
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
            val job =
                try {
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

    /** @return タイルの PNG。描くものが無いときは null。 */
    private fun renderTileInternal(
        request: TileRequest,
        tileState: TileState,
    ): ByteArray? {
        if (tileState.features.isEmpty()) return null

        val z = request.z
        val worldTileCount = 1 shl z
        // x は世界を巻き回す（日付変更線をまたいだ要求が来る）。y は範囲外なら描かない。
        val x = ((request.x % worldTileCount) + worldTileCount) % worldTileCount
        val y = request.y
        if (y !in 0 until worldTileCount) return null

        val pixelRatio = request.pixelRatio
        val context =
            TilePaintContext(
                zoom = z,
                worldSize = tileSize.toDouble() * worldTileCount,
                originX = x.toDouble() * tileSize,
                originY = y.toDouble() * tileSize,
                tileMinX = x.toDouble() / worldTileCount,
                tileMinY = y.toDouble() / worldTileCount,
                tileMaxX = (x + 1).toDouble() / worldTileCount,
                tileMaxY = (y + 1).toDouble() / worldTileCount,
                pixelRatio = pixelRatio,
            )

        val candidates =
            tileState.index?.query(context.tileMinX, context.tileMinY, context.tileMaxX, context.tileMaxY)
                ?: tileState.features.indices.toList()

        val bitmap = painter.beginTile(tileSize * pixelRatio)
        val canvas = Canvas(bitmap)
        canvas.scale(pixelRatio.toFloat(), pixelRatio.toFloat())

        var hasContent = false
        for (idx in candidates) {
            val feature = tileState.features[idx]
            if (!feature.bounds.intersects(
                    context.tileMinX,
                    context.tileMinY,
                    context.tileMaxX,
                    context.tileMaxY,
                )
            ) {
                continue
            }
            if (painter.drawFeature(canvas, feature, context)) {
                hasContent = true
            }
        }

        if (!hasContent) return null
        return painter.toPng(bitmap)
    }

    /**
     * Returns the topmost [GeoJSONFeature] whose geometry contains or is near
     * [longitude]/[latitude], or null if nothing is hit.
     * Call this from a map-level onClick handler on the main thread.
     */
    fun hitTest(
        longitude: Double,
        latitude: Double,
        lineTolSq: Double? = null,
        pointTolSq: Double? = null,
    ): GeoJSONHitTestResult? {
        val currentState = state
        return hitTester.hitTest(
            longitude = longitude,
            latitude = latitude,
            features = currentState.features,
            index = currentState.index,
            lineTolSq = lineTolSq,
            pointTolSq = pointTolSq,
        )
    }

    fun hitTestFeature(
        longitude: Double,
        latitude: Double,
        lineTolSq: Double? = null,
        pointTolSq: Double? = null,
    ): GeoJSONFeature? = hitTest(longitude, latitude, lineTolSq, pointTolSq)?.feature

    data class LayerStyle(
        val strokeColor: Int,
        val fillColor: Int,
        val strokeWidth: Float,
        val pointRadius: Float,
    )

    data class GeoJSONHitTestResult(
        val feature: GeoJSONFeature,
        val position: GeoPoint,
    )

    /** 描画中に元データが差し替わっても矛盾しないよう、1 回ぶんをまとめて固めたもの。 */
    private data class TileState(
        val features: List<RenderFeature>,
        val index: GeoJSONSpatialIndex?,
    )

    private data class RenderJob(
        val key: String,
        val epoch: Long,
        val request: TileRequest,
        val state: TileState,
        val future: CompletableFuture<ByteArray?>,
    )

    companion object {
        private const val DEFAULT_CACHE_SIZE_KB = 8 * 1024
        private val DEFAULT_MAX_CONCURRENT_RENDERS =
            (Runtime.getRuntime().availableProcessors() - 1).coerceIn(2, 6)
        private const val MAX_CONCURRENT_RENDERS = 6
        private const val MAX_QUEUE_SIZE = 512
    }
}
