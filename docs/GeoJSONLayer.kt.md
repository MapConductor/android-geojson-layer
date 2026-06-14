# GeoJSONLayer

Composable raster-backed layer for rendering GeoJSON features on a Map Conductor map.

## Signature

```kotlin
@Composable
fun MapViewScope.GeoJSONLayer(
    state: GeoJSONLayerState = remember { GeoJSONLayerState() },
    features: List<GeoJSONFeature> = emptyList(),
    tileSize: Int = GeoJSONDefaults.DEFAULT_TILE_SIZE,
    disableTileServerCache: Boolean = false,
    content: @Composable MapViewScope.() -> Unit = {},
)
```

## Description

`GeoJSONLayer` renders GeoJSON vector data into raster tiles served by `LocalTileServer`. Static
features are supplied through `features`, while dynamic child features can be declared inside the
`content` lambda with `GeoJSONFeature` or `GeoJSONFeatures`.

The layer creates a `GeoJSONTileRenderer`, registers it with the local tile server, and displays the
result as a normal `RasterLayer`. When feature data or layer style changes, rendered tiles are
invalidated and the raster source URL is refreshed.

## Parameters

- `state`
    - Type: `GeoJSONLayerState`
    - Description: Layer style, visibility, opacity, zoom limits, and click handling state.
- `features`
    - Type: `List<GeoJSONFeature>`
    - Description: Static features rendered by the layer.
- `tileSize`
    - Type: `Int`
    - Description: Tile size in pixels.
- `disableTileServerCache`
    - Type: `Boolean`
    - Description: When `true`, disables local tile server cache behavior for this layer.
- `content`
    - Type: `@Composable MapViewScope.() -> Unit`
    - Description: Dynamic feature content for the layer.

## Usage

```kotlin
GeoJSONLayer(
    state = layerState,
    features = parsedFeatures,
) {
    GeoJSONFeature(state = liveFeature)
}
```

