package nl.giejay.android.tv.immich.homeassistant

import com.google.gson.JsonElement
import com.google.gson.JsonObject

/** One Home Assistant entity's current state, kept up to date by [HaConnection]. */
class HaEntity(
    val id: String,
    var state: String,
    var attributes: JsonObject,
    /** Epoch seconds of the last state change. */
    var lastChanged: Double
) {
    val domain: String get() = id.substringBefore('.')
    val objectId: String get() = id.substringAfter('.')

    fun attr(name: String): JsonElement? = attributes.get(name)?.takeUnless { it.isJsonNull }

    fun attrString(name: String): String? = attr(name)?.takeIf { it.isJsonPrimitive }?.asString

    fun attrDouble(name: String): Double? =
        attr(name)?.takeIf { it.isJsonPrimitive }?.let { runCatching { it.asDouble }.getOrNull() }

    val friendlyName: String
        get() = attrString("friendly_name")?.takeIf { it.isNotBlank() }
            ?: objectId.replace('_', ' ').replaceFirstChar { it.uppercaseChar() }

    val deviceClass: String? get() = attrString("device_class")
    val unit: String? get() = attrString("unit_of_measurement")

    val isUnavailable: Boolean get() = state == "unavailable" || state == "unknown"
}
