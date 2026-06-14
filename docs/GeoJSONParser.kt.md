# GeoJSONParser

Parser for standard GeoJSON documents.

## Signature

```kotlin
object GeoJSONParser
```

## Description

`GeoJSONParser` supports GeoJSON `FeatureCollection`, `Feature`, and bare geometry objects. It can
parse complete JSON strings into `GeoJSONFeatureState` values or stream feature collections from an
`InputStream` to reduce memory usage.

Supported geometry types include `Point`, `MultiPoint`, `LineString`, `MultiLineString`, `Polygon`,
`MultiPolygon`, and `GeometryCollection`.

## Methods

### parse

```kotlin
fun parse(json: String): List<GeoJSONFeatureState>
```

Parses a GeoJSON string and returns mutable feature states.

### parseStream

```kotlin
fun parseStream(input: InputStream): List<GeoJSONFeature>
```

Parses features from an input stream and returns immutable feature models.

### streamParse

```kotlin
fun streamParse(
    input: InputStream,
    onFeature: (GeoJSONFeature) -> Unit,
)
```

Streams each parsed feature to `onFeature`. This is useful for large `FeatureCollection` documents.

