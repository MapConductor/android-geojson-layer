# GeoJSONLayerState

Mutable state for a `GeoJSONLayer`.

## Signature

```kotlin
class GeoJSONLayerState(
    opacity: Float = GeoJSONDefaults.DEFAULT_OPACITY,
    strokeColor: Int = GeoJSONDefaults.DEFAULT_STROKE_COLOR,
    fillColor: Int = GeoJSONDefaults.DEFAULT_FILL_COLOR,
    strokeWidth: Float = GeoJSONDefaults.DEFAULT_STROKE_WIDTH,
    pointRadius: Float = GeoJSONDefaults.DEFAULT_POINT_RADIUS,
    visible: Boolean = true,
    minZoom: Int = 0,
    maxZoom: Int = GeoJSONDefaults.DEFAULT_MAX_ZOOM,
    onClick: ((GeoJSONFeature, GeoJSONGeometry.LonLat) -> Boolean)? = null,
)
```

## Properties

- `opacity`
    - Type: `Float`
    - Description: Raster layer opacity from `0.0f` to `1.0f`.
- `strokeColor`
    - Type: `Int`
    - Description: Default stroke color for line and polygon features.
- `fillColor`
    - Type: `Int`
    - Description: Default fill color for polygon and point features.
- `strokeWidth`
    - Type: `Float`
    - Description: Default stroke width in pixels.
- `pointRadius`
    - Type: `Float`
    - Description: Default point radius in pixels.
- `visible`
    - Type: `Boolean`
    - Description: Whether the layer is visible.
- `minZoom`, `maxZoom`
    - Type: `Int`
    - Description: Zoom range where the generated raster layer should be shown.
- `onClick`
    - Type: `((GeoJSONFeature, GeoJSONGeometry.LonLat) -> Boolean)?`
    - Description: Optional hit-test callback. Return `true` when the click is consumed.

## Methods

- `processClick(geoPoint)`
    - Performs a renderer hit test and invokes `onClick` when a feature is found.
- `copy(...)`
    - Creates a new state object with selected values replaced.

## GeoJSONDefaults

Default style and rendering constants for GeoJSON layers, including opacity, colors, stroke width,
point radius, tile size, and maximum zoom.

