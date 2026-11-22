package nl.giejay.android.tv.immich.homeassistant

import com.google.gson.JsonElement
import com.google.gson.JsonObject

/** One entity the frame's Home Assistant dashboard shows, with the name the card gives it. */
data class DashboardRef(
    val entityId: String,
    /** The card's own "name" for it (not a template), or null to use the entity's friendly name. */
    val name: String?,
    /** Index of the top-level card (or badge) it sits in: entities in one card belong together. */
    val card: Int
)

/**
 * Finds the entities on the configured dashboard. The dashboard is the owner's curated list of
 * what matters on the frame, so the ambient screens draw from it instead of a hard-coded list of
 * entity IDs: which of them is the weather, a person, a smoke alarm or a room temperature is then
 * decided from each entity's domain and device class ([House]).
 */
object DashboardScan {

    /** "dashboard-id" or "dashboard-id/view-path" (the Home Assistant > Dashboard setting). */
    fun parseSetting(setting: String): Pair<String?, String?> {
        val trimmed = setting.trim().trim('/')
        val dashboard = trimmed.substringBefore('/').ifEmpty { null }?.takeUnless { it == "lovelace" }
        val view = trimmed.substringAfter('/', "").ifEmpty { null }
        return dashboard to view
    }

    /** The view to read: by path, else by index, else the first one. */
    fun pickView(config: JsonObject, viewKey: String?): JsonObject? {
        val views = config.get("views")?.takeIf { it.isJsonArray }?.asJsonArray
            ?.mapNotNull { it.takeIf { v -> v.isJsonObject }?.asJsonObject }.orEmpty()
        val byKey = viewKey?.let { key ->
            views.firstOrNull { it.get("path")?.takeIf { p -> p.isJsonPrimitive }?.asString == key }
                ?: key.toIntOrNull()?.let { views.getOrNull(it) }
        }
        return byKey ?: views.firstOrNull()
    }

    /** Every entity referenced by [view], in reading order, first occurrence wins. */
    fun scan(view: JsonObject): List<DashboardRef> {
        val out = LinkedHashMap<String, DashboardRef>()
        var card = 0
        fun visitCard(element: JsonElement) {
            walk(element, card, out)
            card++
        }
        view.get("badges")?.takeIf { it.isJsonArray }?.asJsonArray?.forEach { visitCard(it) }
        view.get("cards")?.takeIf { it.isJsonArray }?.asJsonArray?.forEach { visitCard(it) }
        view.get("sections")?.takeIf { it.isJsonArray }?.asJsonArray?.forEach { section ->
            if (!section.isJsonObject) return@forEach
            val cards = section.asJsonObject.get("cards")
            if (cards != null && cards.isJsonArray) cards.asJsonArray.forEach { visitCard(it) } else visitCard(section)
        }
        return out.values.toList()
    }

    private fun walk(element: JsonElement, card: Int, out: MutableMap<String, DashboardRef>) {
        when {
            element.isJsonObject -> {
                val obj = element.asJsonObject
                entityOf(obj.get("entity"))?.let { id -> add(out, id, nameOf(obj), card) }
                entityOf(obj.get("camera_image"))?.let { id -> add(out, id, null, card) }
                obj.entrySet().forEach { (key, value) ->
                    when {
                        key == "entity" || key == "camera_image" -> Unit
                        key == "entities" && value.isJsonArray -> value.asJsonArray.forEach { item ->
                            val id = entityOf(item)
                            if (id != null) add(out, id, null, card) else walk(item, card, out)
                        }
                        value.isJsonObject || value.isJsonArray -> walk(value, card, out)
                    }
                }
            }
            element.isJsonArray -> element.asJsonArray.forEach { walk(it, card, out) }
        }
    }

    private fun add(out: MutableMap<String, DashboardRef>, id: String, name: String?, card: Int) {
        if (!out.containsKey(id)) out[id] = DashboardRef(id, name, card)
    }

    private val ENTITY_ID = Regex("^[a-z_]+\\.[a-z0-9_]+$")

    private fun entityOf(element: JsonElement?): String? =
        element?.takeIf { it.isJsonPrimitive }?.asString?.trim()?.takeIf { ENTITY_ID.matches(it) }

    private fun nameOf(obj: JsonObject): String? =
        obj.get("name")?.takeIf { it.isJsonPrimitive }?.asString?.trim()
            ?.takeIf { it.isNotEmpty() && !it.contains("{{") && !it.contains("{%") && !it.contains("[[[") }
}
