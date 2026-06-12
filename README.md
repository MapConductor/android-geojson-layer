# MapConductor GeoJSON Layer

`android-geojson-layer` adds a tile-rendered GeoJSON overlay to MapConductor map views.
It parses GeoJSON data into feature models, renders the features through MapConductor's
raster tile layer pipeline, and provides hit-testing for feature selection.

The layer is useful for large GeoJSON datasets because rendering is tile based and parsed
features can be supplied as lightweight data objects instead of one Compose state object
per feature.

## Features

- Renders `Point`, `MultiPoint`, `LineString`, `MultiLineString`, `Polygon`,
  `MultiPolygon`, and `GeometryCollection`.
- Supports static bulk features with `GeoJSONFeature`.
- Supports reactive Compose features with `GeoJSONFeatureState`, `GeoJSONFeature`,
  and `GeoJSONFeatures`.
- Streams `FeatureCollection` input with `GeoJSONParser.parseStream`.
- Streams GeoJSON Text Sequences with `GeoJSONSeqParser`.
- Supports layer-level and feature-level styling.
- Provides touch hit-testing through `GeoJSONLayerState.processClick`.

## Installation

When developing inside the MapConductor SDK repository, include the local Gradle module:

```kotlin
dependencies {
    implementation(project(":android-geojson-layer"))
}
```

For published artifacts, use the configured MapConductor coordinates:

```kotlin
dependencies {
    implementation("com.mapconductor:geojson:<version>")
}
```

The module depends on MapConductor core and Jetpack Compose runtime.

## Basic Usage

Load a GeoJSON `FeatureCollection` and render it inside any MapConductor map view content
scope:

```kotlin
@Composable
fun GeoJsonMap() {
    val context = LocalContext.current
    val mapViewState = rememberMapLibreMapViewState(
        cameraPosition = MapCameraPosition(
            position = GeoPoint(35.68, 139.77),
            zoom = 13.0,
        ),
    )

    var features by remember { mutableStateOf<List<GeoJSONFeature>>(emptyList()) }

    LaunchedEffect(Unit) {
        features = withContext(Dispatchers.IO) {
            context.assets.open("areas.geojson").use { input ->
                GeoJSONParser.parseStream(input)
            }
        }
    }

    MapLibreMapView(state = mapViewState) {
        GeoJSONLayer(features = features)
    }
}
```

`parseStream` expects a top-level GeoJSON `FeatureCollection`. For a small JSON string
or a single feature, use `GeoJSONParser.parse`.

## Styling

Set default layer style through `GeoJSONLayerState`:

```kotlin
val layerState = remember {
    GeoJSONLayerState(
        strokeColor = android.graphics.Color.argb(220, 30, 136, 229),
        fillColor = android.graphics.Color.argb(60, 30, 136, 229),
        strokeWidth = 1.5f,
        pointRadius = 8f,
        opacity = 1f,
        visible = true,
    )
}

GeoJSONLayer(
    state = layerState,
    features = features,
)
```

Individual `GeoJSONFeature` and `GeoJSONFeatureState` objects can override
`strokeColor`, `fillColor`, `strokeWidth`, `pointRadius`, and `visible`.

## Touch Detection

The layer keeps hit-testing in `android-geojson-layer` and avoids changing MapConductor
core. Because MapConductor currently exposes a single map click listener slot, apps
should forward map clicks to the `GeoJSONLayerState` manually.

```kotlin
var selectedFeature by remember { mutableStateOf<GeoJSONFeature?>(null) }

val layerState = remember {
    GeoJSONLayerState(
        onClick = { feature, position ->
            selectedFeature = feature
            // Use position for marker, info bubble, bottom sheet, etc.
        },
    )
}

MapLibreMapView(
    state = mapViewState,
    onMapClick = { point ->
        val consumed = layerState.processClick(point)
        if (!consumed) {
            selectedFeature = null
        }
    },
) {
    GeoJSONLayer(
        state = layerState,
        features = features,
    )
}
```

`processClick` returns `true` when a feature is hit and the layer `onClick` callback is
invoked. It returns `false` when no rendered feature is found at that map position.

Hit-testing supports points, lines, polygons with holes, multiparts, and geometry
collections. When features overlap, the latest rendered matching feature is returned.

## Reactive Features

For small or frequently changing feature sets, place stateful features inside the layer:

```kotlin
val pointState = remember {
    GeoJSONFeatureState(
        featureId = "office",
        geometry = GeoJSONGeometry.Point(
            longitude = 139.77,
            latitude = 35.68,
        ),
        properties = mapOf("name" to "Office"),
    )
}

GeoJSONLayer(state = layerState) {
    GeoJSONFeature(pointState)
}
```

For many static features, prefer `GeoJSONFeature` plus the `features` parameter. That
path avoids creating Compose snapshot state for every feature.

## GeoJSON Text Sequences

GeoJSON Text Sequences are supported for stream-friendly datasets where records are
separated by the RFC 8142 record separator character.

```kotlin
val features = withContext(Dispatchers.IO) {
    GeoJSONSeqParser.parse(file)
}
```

Use `GeoJSONSeqParser.streamParse` to consume records one at a time.

## Performance Notes

- Prefer `GeoJSONParser.parseStream(input)` for large `FeatureCollection` files.
- Load and parse data on `Dispatchers.IO` or another background dispatcher.
- Use static `GeoJSONFeature` lists for large datasets.
- Use `GeoJSONFeatureState` only when individual feature updates are needed.
- The layer internally invalidates its tile URL when feature data or style changes so map
  SDK raster caches request fresh rendered tiles.

## Current Limitations

- Automatic layer-level click listener registration is intentionally not implemented.
  Forward map clicks to `GeoJSONLayerState.processClick`.
- `GeoJSONParser.parseStream` supports top-level `FeatureCollection` input. Use
  `GeoJSONParser.parse` for top-level `Feature` or bare geometry JSON strings.
- Hit-test line and point tolerances are renderer constants in world coordinates, not
  configurable public API yet.
- The layer is rendered as raster tiles, so native SDK vector feature querying is not used.

## Development

Compile the layer and sample app:

```sh
./gradlew :android-geojson-layer:compileDebugKotlin :simple-map-app:compileDebugKotlin
```

Run ktlint for the module:

```sh
./gradlew :android-geojson-layer:ktlintCheck
```
