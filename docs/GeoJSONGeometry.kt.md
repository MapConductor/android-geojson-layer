# GeoJSONGeometry

Sealed model for GeoJSON geometry values.

## Signature

```kotlin
sealed class GeoJSONGeometry
```

## Geometry Types

- `Point`
    - Stores one `LonLat` coordinate.
- `MultiPoint`
    - Stores a list of point coordinates.
- `LineString`
    - Stores an ordered list of coordinates.
- `MultiLineString`
    - Stores multiple line strings.
- `Polygon`
    - Stores polygon rings. The first ring is the outer boundary and subsequent rings are holes.
- `MultiPolygon`
    - Stores multiple polygons.
- `GeometryCollection`
    - Stores a list of nested geometries.
- `Empty`
    - Represents an empty or unsupported geometry.

## LonLat

```kotlin
data class LonLat(
    val longitude: Double,
    val latitude: Double,
)
```

GeoJSON coordinates are stored in longitude, latitude order to match the GeoJSON specification.

