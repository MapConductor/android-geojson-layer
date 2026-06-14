# GeoJSONFeatureState

Mutable, Compose-aware feature state for dynamic GeoJSON rendering.

## Signature

```kotlin
class GeoJSONFeatureState(
    id: String? = null,
    geometry: GeoJSONGeometry,
    properties: Map<String, Any?> = emptyMap(),
    strokeColor: Int? = null,
    fillColor: Int? = null,
    strokeWidth: Float? = null,
    pointRadius: Float? = null,
    visible: Boolean = true,
)
```

## Description

`GeoJSONFeatureState` is intended for features that need to change after creation. Geometry,
properties, style, and visibility are backed by Compose mutable state, allowing `GeoJSONLayer` to
re-render affected tiles when feature state changes.

## Properties

- `id`
    - Type: `String`
    - Description: Stable feature identifier. If no id is supplied, one is derived from geometry and
      properties.
- `geometry`
    - Type: `GeoJSONGeometry`
    - Description: Current feature geometry.
- `properties`
    - Type: `Map<String, Any?>`
    - Description: Feature attributes.
- `strokeColor`, `fillColor`, `strokeWidth`, `pointRadius`
    - Type: nullable style values
    - Description: Per-feature style overrides.
- `visible`
    - Type: `Boolean`
    - Description: Controls whether the feature is rendered and hit tested.

## Methods

- `fingerPrint()`
    - Returns a `GeoJSONFeatureFingerPrint` used to detect render-affecting changes.
- `asFlow()`
    - Returns a `Flow<GeoJSONFeatureFingerPrint>` for observing feature changes.

## GeoJSONFeatureFingerPrint

Snapshot of the fields that affect rendering.

```kotlin
data class GeoJSONFeatureFingerPrint(
    val geometry: GeoJSONGeometry,
    val properties: Map<String, Any?>,
    val strokeColor: Int?,
    val fillColor: Int?,
    val strokeWidth: Float?,
    val pointRadius: Float?,
    val visible: Boolean,
)
```

