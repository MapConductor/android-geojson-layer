package com.mapconductor.geojson

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import com.mapconductor.core.ChildCollector
import com.mapconductor.core.MapViewScope

val LocalGeoJSONFeatureCollector =
    compositionLocalOf<ChildCollector<GeoJSONFeatureState>> {
        error("GeoJSONFeature must be placed inside a GeoJSONLayer composable")
    }

@Composable
fun MapViewScope.GeoJSONFeature(state: GeoJSONFeatureState) {
    val collector = LocalGeoJSONFeatureCollector.current
    LaunchedEffect(state) {
        collector.add(state)
    }
    DisposableEffect(state.id) {
        onDispose {
            collector.remove(state.id)
        }
    }
}

@Composable
fun MapViewScope.GeoJSONFeatures(states: List<GeoJSONFeatureState>) {
    val collector = LocalGeoJSONFeatureCollector.current
    LaunchedEffect(states, states.size) {
        collector.replaceAll(states)
    }
    DisposableEffect(Unit) {
        onDispose {
            collector.replaceAll(emptyList())
        }
    }
}
