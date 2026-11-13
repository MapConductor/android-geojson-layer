package com.mapconductor.geojson

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.mapconductor.core.ComponentState
import com.mapconductor.core.StateMutationSignal

class GeoJSONFeatureState(
    featureId: String? = null,
    geometry: GeoJSONGeometry,
    properties: Map<String, Any?> = emptyMap(),
    strokeColor: Int? = null,
    fillColor: Int? = null,
    strokeWidth: Float? = null,
    pointRadius: Float? = null,
    visible: Boolean = true,
) : ComponentState {
    override val id: String = featureId ?: buildDefaultId(geometry, properties)

    /**
     * Writes to the fields below are announced here rather than discovered by
     * reading them all back. See [StateMutationSignal].
     */
    override val mutations = StateMutationSignal()

    var geometry by mutations.notifying(geometry)
    var properties by mutations.notifying(properties)
    var strokeColor by mutations.notifying(strokeColor)
    var fillColor by mutations.notifying(fillColor)
    var strokeWidth by mutations.notifying(strokeWidth)
    var pointRadius by mutations.notifying(pointRadius)
    var visible by mutations.notifying(visible)

    fun fingerPrint(): GeoJSONFeatureFingerPrint =
        GeoJSONFeatureFingerPrint(
            id = id.hashCode(),
            geometry = geometry.hashCode(),
            properties = properties.hashCode(),
            style = styleHashCode(),
            visible = visible.hashCode(),
        )

    private fun styleHashCode(): Int {
        var h = strokeColor.hashCode()
        h = 31 * h + fillColor.hashCode()
        h = 31 * h + strokeWidth.hashCode()
        h = 31 * h + pointRadius.hashCode()
        return h
    }

    companion object {
        private fun buildDefaultId(
            geometry: GeoJSONGeometry,
            properties: Map<String, Any?>,
        ): String {
            var h = geometry.hashCode()
            h = 31 * h + properties.hashCode()
            return h.toString()
        }
    }
}

data class GeoJSONFeatureFingerPrint(
    val id: Int,
    val geometry: Int,
    val properties: Int,
    val style: Int,
    val visible: Int,
)
