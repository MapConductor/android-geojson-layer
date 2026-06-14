# GeoJSONTileRenderer

Tile renderer that draws GeoJSON features into raster map tiles.

## Signature

```kotlin
class GeoJSONTileRenderer(
    private val tileSize: Int = GeoJSONDefaults.DEFAULT_TILE_SIZE,
) : TileProviderInterface
```

## Description

`GeoJSONTileRenderer` converts static and dynamic GeoJSON features into PNG tile responses for
`LocalTileServer`. It maintains an in-memory tile cache, deduplicates in-flight tile work, and
rebuilds render data when features or layer style change.

The renderer also provides hit testing so `GeoJSONLayerState.onClick` can identify the feature under
a map click.

## Methods

### update

```kotlin
fun update(
    staticFeatures: List<GeoJSONFeature>,
    dynamicFeatures: List<GeoJSONFeatureState>,
    layerStyle: GeoJSONLayerState,
)
```

Updates the feature set and style used for future tile renders. Existing cached tiles are
invalidated when render data changes.

### getTile

```kotlin
override suspend fun getTile(request: TileRequest): ByteArray?
```

Returns PNG bytes for the requested tile, or `null` when no tile can be rendered.

### hitTest

```kotlin
fun hitTest(
    longitude: Double,
    latitude: Double,
): GeoJSONFeature?
```

Returns the top matching visible feature near the supplied coordinate, if one exists.

## Rendering Notes

- Features outside the requested tile are skipped.
- Point, line, polygon, multi-geometry, and geometry collection values are supported.
- Per-feature style overrides take precedence over `GeoJSONLayerState` defaults.
- Tiles are encoded through `FastPngEncoder`.

