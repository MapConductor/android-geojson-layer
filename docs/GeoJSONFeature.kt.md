# GeoJSONFeature

Immutable data model for a GeoJSON feature used by `GeoJSONLayer` static feature rendering.

## Signature

```kotlin
data class GeoJSONFeature(
    val id: String?,
    val geometry: GeoJSONGeometry,
    val properties: Map<String, Any?> = emptyMap(),
    val strokeColor: Int? = null,
    val fillColor: Int? = null,
    val strokeWidth: Float? = null,
    val pointRadius: Float? = null,
    val visible: Boolean = true,
)
```

## Properties

- `id`
    - Type: `String?`
    - Description: Optional stable feature identifier.
- `geometry`
    - Type: `GeoJSONGeometry`
    - Description: Geometry to render.
- `properties`
    - Type: `Map<String, Any?>`
    - Description: Original GeoJSON properties associated with the feature.
- `strokeColor`, `fillColor`, `strokeWidth`, `pointRadius`
    - Type: nullable style values
    - Description: Per-feature style overrides. When `null`, the layer defaults are used.
- `visible`
    - Type: `Boolean`
    - Description: Whether the feature should be included in rendering and hit testing.

