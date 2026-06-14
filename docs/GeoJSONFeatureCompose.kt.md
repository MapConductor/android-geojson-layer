# GeoJSONFeatureCompose

Compose helpers for declaring reactive GeoJSON features inside a `GeoJSONLayer`.

## LocalGeoJSONFeatureCollector

Composition local that exposes the active child feature collector while `GeoJSONLayer` content is
being composed.

```kotlin
val LocalGeoJSONFeatureCollector: ProvidableCompositionLocal<ChildCollector<GeoJSONFeatureState>?>
```

## GeoJSONFeature

### Signature

```kotlin
@Composable
fun MapViewScope.GeoJSONFeature(state: GeoJSONFeatureState)
```

### Description

Adds a single `GeoJSONFeatureState` to the current `GeoJSONLayer`. The feature is removed from the
collector when the composable leaves composition.

## GeoJSONFeatures

### Signature

```kotlin
@Composable
fun MapViewScope.GeoJSONFeatures(states: List<GeoJSONFeatureState>)
```

### Description

Adds a list of `GeoJSONFeatureState` objects to the current `GeoJSONLayer`. The collector is updated
when the list changes and cleaned up when the composable leaves composition.

## Usage

```kotlin
GeoJSONLayer(state = layerState) {
    GeoJSONFeature(state = selectedFeatureState)
}
```

