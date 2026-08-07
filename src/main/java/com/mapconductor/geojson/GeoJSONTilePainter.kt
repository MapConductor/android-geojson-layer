package com.mapconductor.geojson

import java.nio.ByteBuffer
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Path

/** タイル 1 枚を描くときの、世界座標→ピクセルの対応と足切り用の範囲。 */
internal data class TilePaintContext(
    val zoom: Int,
    val worldSize: Double,
    val originX: Double,
    val originY: Double,
    val tileMinX: Double,
    val tileMinY: Double,
    val tileMaxX: Double,
    val tileMaxY: Double,
    val pixelRatio: Int,
)

/**
 * フィーチャーを `Canvas` へ描き、PNG にする部分。
 *
 * `Bitmap` / `Path` / ピクセルバッファは**スレッドごとに使い回す**。タイル 1 枚ごとに
 * 確保すると、1 画面数十枚では GC を強く叩く。
 *
 * ios-sdk の `GeoJSONTilePainter.swift` / react-sdk の `GeoJSONTilePainter.ts` と
 * 同じ描き方（あちらは Canvas2D / CoreGraphics）。
 */
internal class GeoJSONTilePainter(
    private val tileSize: Int,
) {
    private val threadLocalBitmap = ThreadLocal<Bitmap>()
    private val threadLocalPath = ThreadLocal<Path>()
    private val threadLocalPixelBuffer = ThreadLocal<ByteBuffer>()
    private val threadLocalRgba = ThreadLocal<ByteArray>()

    /** タイル用の透明なビットマップを用意する。 */
    fun beginTile(renderSize: Int): Bitmap {
        val bitmap = getBitmap(renderSize)
        bitmap.eraseColor(android.graphics.Color.TRANSPARENT)
        return bitmap
    }

    /** @return 何か描いたとき true。 */
    fun drawFeature(
        canvas: Canvas,
        feature: RenderFeature,
        context: TilePaintContext,
    ): Boolean = drawGeometry(canvas, feature, feature.worldGeometry, context)

    private fun drawGeometry(
        canvas: Canvas,
        feature: RenderFeature,
        geometry: WorldGeometry,
        context: TilePaintContext,
    ): Boolean =
        when (geometry) {
            is WorldGeometry.Point -> {
                val px = GeoJSONWorld.toPixel(geometry.wx, context.worldSize, context.originX)
                val py = GeoJSONWorld.toPixel(geometry.wy, context.worldSize, context.originY)
                canvas.drawCircle(px, py, feature.pointRadius, feature.fillPaint)
                feature.strokePaint?.let { canvas.drawCircle(px, py, feature.pointRadius, it) }
                true
            }

            is WorldGeometry.Points -> {
                val points = geometry.points
                var i = 0
                while (i < points.size) {
                    val px = GeoJSONWorld.toPixel(points[i], context.worldSize, context.originX)
                    val py = GeoJSONWorld.toPixel(points[i + 1], context.worldSize, context.originY)
                    canvas.drawCircle(px, py, feature.pointRadius, feature.fillPaint)
                    feature.strokePaint?.let { canvas.drawCircle(px, py, feature.pointRadius, it) }
                    i += 2
                }
                points.isNotEmpty()
            }

            is WorldGeometry.Line -> {
                val strokeWidth = feature.strokePaint?.strokeWidth ?: feature.fillPaint.strokeWidth
                val path = buildLinePath(geometry.rings, context, strokeWidth)
                if (!path.isEmpty) {
                    canvas.drawPath(path, feature.strokePaint ?: feature.fillPaint)
                    true
                } else {
                    false
                }
            }

            is WorldGeometry.Polygon -> {
                val path = buildPolygonPath(geometry.rings, context)
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
                    if (drawGeometry(canvas, feature, part, context)) {
                        drew = true
                    }
                }
                drew
            }

            WorldGeometry.Empty -> false
        }

    /**
     * タイルにかかる線分だけをつないだパスを作る。
     *
     * 余白を取ってから足切りするのは、線の太さぶん外側の線分もタイルに
     * はみ出して見えるため。範囲外の線分では `moveTo` に戻し、離れた点を
     * 直線でつながないようにする。
     */
    private fun buildLinePath(
        rings: List<WorldRing>,
        context: TilePaintContext,
        strokeWidth: Float,
    ): Path {
        val path = getPath()
        path.rewind()
        val margin = ((context.tileMaxX - context.tileMinX) * 0.25) + (strokeWidth.toDouble() / context.worldSize)
        val minX = context.tileMinX - margin
        val minY = context.tileMinY - margin
        val maxX = context.tileMaxX + margin
        val maxY = context.tileMaxY + margin
        for (ring in rings) {
            val coords = ring.coordsForZoom(context.zoom, tileSize, context.pixelRatio)
            if (coords.size < 4) continue
            var needsMove = true
            var i = 2
            while (i < coords.size) {
                val ax = coords[i - 2]
                val ay = coords[i - 1]
                val bx = coords[i]
                val by = coords[i + 1]
                if (!GeoJSONWorld.segmentOutside(ax, ay, bx, by, minX, minY, maxX, maxY)) {
                    if (needsMove) {
                        path.moveTo(
                            GeoJSONWorld.toPixel(ax, context.worldSize, context.originX),
                            GeoJSONWorld.toPixel(ay, context.worldSize, context.originY),
                        )
                        needsMove = false
                    }
                    path.lineTo(
                        GeoJSONWorld.toPixel(bx, context.worldSize, context.originX),
                        GeoJSONWorld.toPixel(by, context.worldSize, context.originY),
                    )
                } else {
                    needsMove = true
                }
                i += 2
            }
        }
        return path
    }

    /** 多角形は穴を扱うため EVEN_ODD で塗る（外環と内環を同じパスに入れる）。 */
    private fun buildPolygonPath(
        rings: List<WorldRing>,
        context: TilePaintContext,
    ): Path {
        val path = getPath()
        path.rewind()
        path.fillType = Path.FillType.EVEN_ODD
        for (ring in rings) {
            val coords = ring.coordsForZoom(context.zoom, tileSize, context.pixelRatio)
            if (coords.size < 6) continue
            path.moveTo(
                GeoJSONWorld.toPixel(coords[0], context.worldSize, context.originX),
                GeoJSONWorld.toPixel(coords[1], context.worldSize, context.originY),
            )
            var i = 2
            while (i < coords.size) {
                path.lineTo(
                    GeoJSONWorld.toPixel(coords[i], context.worldSize, context.originX),
                    GeoJSONWorld.toPixel(coords[i + 1], context.worldSize, context.originY),
                )
                i += 2
            }
            path.close()
        }
        return path
    }

    /**
     * ビットマップを PNG のバイト列にする。
     *
     * `copyPixelsToBuffer` は ARGB_8888 の内部表現、つまり R,G,B,A の並びで
     * **アルファ乗算済み**を返す。PNG は非乗算なので、半透明のピクセルだけ割り戻す。
     */
    fun toPng(bitmap: Bitmap): ByteArray {
        val byteCount = bitmap.byteCount
        val buffer = getPixelBuffer(byteCount)
        buffer.clear()
        bitmap.copyPixelsToBuffer(buffer)
        val source = buffer.array()
        val rgba = getRgbaBuffer(byteCount)
        var i = 0
        while (i < byteCount) {
            when (val a = source[i + 3].toInt() and 0xff) {
                255 -> {
                    rgba[i] = source[i]
                    rgba[i + 1] = source[i + 1]
                    rgba[i + 2] = source[i + 2]
                    rgba[i + 3] = source[i + 3]
                }
                0 -> {
                    rgba[i] = 0
                    rgba[i + 1] = 0
                    rgba[i + 2] = 0
                    rgba[i + 3] = 0
                }
                else -> {
                    val half = a / 2
                    rgba[i] = ((((source[i].toInt() and 0xff) * 255 + half) / a).coerceAtMost(255)).toByte()
                    rgba[i + 1] = ((((source[i + 1].toInt() and 0xff) * 255 + half) / a).coerceAtMost(255)).toByte()
                    rgba[i + 2] = ((((source[i + 2].toInt() and 0xff) * 255 + half) / a).coerceAtMost(255)).toByte()
                    rgba[i + 3] = source[i + 3]
                }
            }
            i += 4
        }
        return FastPngEncoder.encode(bitmap.width, bitmap.height, rgba)
    }

    private fun getBitmap(renderSize: Int): Bitmap {
        val existing = threadLocalBitmap.get()
        if (existing != null &&
            !existing.isRecycled &&
            existing.width == renderSize &&
            existing.height == renderSize
        ) {
            return existing
        }
        val bm = Bitmap.createBitmap(renderSize, renderSize, Bitmap.Config.ARGB_8888)
        threadLocalBitmap.set(bm)
        return bm
    }

    private fun getPath(): Path {
        val existing = threadLocalPath.get()
        if (existing != null) return existing
        val path = Path()
        threadLocalPath.set(path)
        return path
    }

    private fun getPixelBuffer(byteCount: Int): ByteBuffer {
        val existing = threadLocalPixelBuffer.get()
        if (existing != null && existing.capacity() >= byteCount && existing.hasArray()) {
            return existing
        }
        val buffer = ByteBuffer.allocate(byteCount)
        threadLocalPixelBuffer.set(buffer)
        return buffer
    }

    private fun getRgbaBuffer(byteCount: Int): ByteArray {
        val existing = threadLocalRgba.get()
        if (existing != null && existing.size >= byteCount) return existing
        val buffer = ByteArray(byteCount)
        threadLocalRgba.set(buffer)
        return buffer
    }
}
