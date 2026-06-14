# GeoJSONSeqParser

Parser for GeoJSON Text Sequences.

## Signature

```kotlin
object GeoJSONSeqParser
```

## Description

`GeoJSONSeqParser` reads RFC 8142-style GeoJSON Text Sequences, where each JSON record is separated
by the ASCII record separator byte `0x1E`. Each record may be a GeoJSON `Feature` or a bare
geometry.

## Methods

### parse

```kotlin
fun parse(input: InputStream): List<GeoJSONFeatureState>
fun parse(file: File): List<GeoJSONFeatureState>
```

Reads all records and returns mutable feature states.

### streamParse

```kotlin
fun streamParse(
    input: InputStream,
    onFeature: (GeoJSONFeatureState) -> Unit,
)

fun streamParse(
    file: File,
    onFeature: (GeoJSONFeatureState) -> Unit,
)
```

Streams parsed records to `onFeature` one at a time.

