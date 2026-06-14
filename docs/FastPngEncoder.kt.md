# FastPngEncoder

Utility object that encodes raw RGBA pixels into a PNG byte array. It is used by the GeoJSON tile
renderer to return rendered vector tiles through the local tile server without depending on Android
`Bitmap` compression APIs for every tile response.

## Signature

```kotlin
object FastPngEncoder
```

## encode

### Signature

```kotlin
fun encode(
    width: Int,
    height: Int,
    rgba: IntArray,
): ByteArray
```

### Description

Creates a PNG image from an array of RGBA pixels. The encoder writes PNG signature, `IHDR`, `IDAT`,
and `IEND` chunks, uses RGBA color type, and compresses scanlines with `Deflater.BEST_SPEED`.

### Parameters

- `width`
    - Type: `Int`
    - Description: Output image width in pixels. Must be greater than zero.
- `height`
    - Type: `Int`
    - Description: Output image height in pixels. Must be greater than zero.
- `rgba`
    - Type: `IntArray`
    - Description: Pixel buffer with exactly `width * height` entries.

### Returns

A PNG-encoded `ByteArray`.

