package nl.giejay.android.tv.immich.homeassistant

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.Html
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.content.res.AppCompatResources
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import nl.giejay.android.tv.immich.R
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Renders one Lovelace view natively (the HA web frontend can't run on KitKat). Supports the
 * common built-in cards; anything else falls back to a list of the entities it references, so a
 * dashboard always shows *something* useful. Read-only: nothing is tappable.
 *
 * Views bind to entity ids; [onEntitiesChanged] re-runs just the affected bindings.
 */
class LovelaceRenderer(
    private val context: Context,
    private val entities: Map<String, HaEntity>,
    private val images: HaImageLoader
) {
    /** Entities the rendered view needs live state for. */
    val entityIds = LinkedHashSet<String>()

    class ForecastRequest(val entityId: String, val type: String, val onForecast: (JsonArray) -> Unit)
    class TemplateRequest(val template: String, val variables: JsonObject? = null, val onResult: (String) -> Unit)
    class HistoryRequest(val entityIds: List<String>, val hours: Int, val onHistory: (Map<String, List<Pair<Double, Double>>>, Double, Double) -> Unit)

    val forecasts = mutableListOf<ForecastRequest>()
    val templates = mutableListOf<TemplateRequest>()
    val histories = mutableListOf<HistoryRequest>()
    /** Re-run periodically (camera snapshots). */
    val periodic = mutableListOf<() -> Unit>()

    private val bindings = HashMap<String, MutableList<() -> Unit>>()
    private val allBindings = mutableListOf<() -> Unit>()
    private val density = context.resources.displayMetrics.density

    fun onEntitiesChanged(ids: Set<String>) {
        ids.forEach { id -> bindings[id]?.forEach { it() } }
    }

    /** Re-runs every binding (relative times like "5 minutes ago"). */
    fun refreshAll() = allBindings.forEach { it() }

    private fun bind(ids: Collection<String>, update: () -> Unit) {
        ids.forEach { id ->
            entityIds += id
            bindings.getOrPut(id) { mutableListOf() } += update
        }
        allBindings += update
        update()
    }

    private fun bindEntity(id: String, update: (HaEntity?) -> Unit) = bind(listOf(id)) { update(entities[id]) }

    // ---- view layout -------------------------------------------------------------------------

    /** Builds the whole view for [widthPx]: optional badges, then columns of cards. */
    fun renderView(view: JsonObject, widthPx: Int): View {
        val root = vertical()
        view.arr("badges")?.let { badges -> if (badges.size() > 0) root.addView(badgeRow(badges), matchWrap(bottom = 14)) }

        val type = view.str("type") ?: if (view.has("sections")) "sections" else "masonry"
        when (type) {
            "panel" -> view.arr("cards")?.firstOrNull()?.asObj()?.let { root.addView(card(it, widthPx), matchWrap()) }
            "sections" -> {
                val sections = view.arr("sections")?.mapNotNull { it.asObj() }.orEmpty()
                val blocks = sections.map { section -> { w: Int -> sectionView(section, w) } }
                root.addView(masonry(blocks, widthPx, maxColumns = view.int("max_columns") ?: 4), matchWrap())
            }
            else -> {
                val cards = view.arr("cards")?.mapNotNull { it.asObj() }.orEmpty()
                val blocks = cards.map { c -> { w: Int -> card(c, w) } }
                root.addView(masonry(blocks, widthPx, maxColumns = 4), matchWrap())
            }
        }
        return root
    }

    /** Lays [blocks] into as many ~300dp columns as fit, each block going to the shortest column. */
    private fun masonry(blocks: List<(Int) -> View>, widthPx: Int, maxColumns: Int): View {
        val gap = dp(12)
        val columns = ((widthPx + gap) / (dp(290) + gap)).coerceIn(1, max(1, minOf(maxColumns, blocks.size)))
        val colWidth = (widthPx - gap * (columns - 1)) / columns
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        val cols = List(columns) { vertical() }
        val heights = IntArray(columns)
        cols.forEachIndexed { i, col ->
            row.addView(col, LinearLayout.LayoutParams(colWidth, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                if (i > 0) leftMargin = gap
            })
        }
        blocks.forEach { build ->
            val v = build(colWidth)
            v.measure(
                View.MeasureSpec.makeMeasureSpec(colWidth, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            )
            val target = heights.indices.minByOrNull { heights[it] } ?: 0
            cols[target].addView(v, matchWrap(bottom = 14))
            heights[target] += v.measuredHeight + gap
        }
        return row
    }

    /** A sections-view section: a 12-column grid (tiles default to half width, headings full). */
    private fun sectionView(section: JsonObject, widthPx: Int): View {
        val box = vertical()
        val cards = section.arr("cards")?.mapNotNull { it.asObj() }.orEmpty()
        val gap = dp(8)
        var row: LinearLayout? = null
        var used = 0
        cards.forEach { cfg ->
            val span = gridColumns(cfg)
            if (row == null || used + span > 12) {
                // spacing is the row's own padding, so a row whose cards are all hidden collapses fully
                row = GridRow(context).apply { setPadding(0, 0, 0, dp(8)) }
                box.addView(row, matchWrap())
                used = 0
            }
            val cardWidth = ((widthPx + gap) * span / 12) - gap
            val v = card(cfg, cardWidth)
            row!!.addView(v, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, span.toFloat()).apply {
                if (used > 0) leftMargin = gap
            })
            used += span
        }
        // pad the last row so a lone half-width tile stays half width
        // MATCH_PARENT like the cards: a fixed-height filler makes LinearLayout size the whole row to it
        row?.let { if (used in 1..11) it.addView(View(context).apply { tag = GridRow.FILLER }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, (12 - used).toFloat())) }
        return box
    }

    private fun gridColumns(cfg: JsonObject): Int {
        cfg.obj("grid_options")?.get("columns")?.let { c ->
            if (c.isJsonPrimitive && c.asJsonPrimitive.isNumber) return c.asInt.coerceIn(1, 12)
            if (c.isJsonPrimitive && c.asString == "full") return 12
        }
        cfg.obj("layout_options")?.get("grid_columns")?.let { c ->
            if (c.isJsonPrimitive && c.asJsonPrimitive.isNumber) return (c.asInt * 3).coerceIn(1, 12)
            if (c.isJsonPrimitive && c.asString == "full") return 12
        }
        return when (cfg.str("type")) {
            "tile", "button" -> 6
            "custom:mushroom-chips-card", "custom:mushroom-title-card" -> 12
            "custom:bubble-card" -> when (cfg.str("card_type")) {
                "separator", "media-player", "horizontal-buttons-stack", "pop-up" -> 12
                else -> 6
            }
            else -> if (cfg.str("type")?.startsWith("custom:mushroom-") == true) 6 else 12
        }
    }

    // ---- cards -------------------------------------------------------------------------------

    fun card(cfg: JsonObject, widthPx: Int): View {
        val v = try {
            when (cfg.str("type")) {
                "tile" -> tileCard(cfg)
                "entity" -> entityCard(cfg)
                "entities" -> entitiesCard(cfg)
                "glance" -> glanceCard(cfg)
                "sensor" -> sensorCard(cfg)
                "gauge" -> gaugeCard(cfg)
                "thermostat", "humidifier" -> thermostatCard(cfg)
                "light" -> lightCard(cfg)
                "button" -> buttonCard(cfg)
                "weather-forecast" -> weatherCard(cfg)
                "markdown" -> markdownCard(cfg)
                "heading" -> headingCard(cfg)
                "picture" -> pictureCard(cfg, widthPx)
                "picture-entity" -> pictureEntityCard(cfg, widthPx)
                "picture-glance" -> pictureGlanceCard(cfg, widthPx)
                "media-control" -> mediaCard(cfg, widthPx)
                "history-graph", "statistics-graph" -> historyCard(cfg)
                "vertical-stack" -> verticalStack(cfg, widthPx)
                "horizontal-stack" -> horizontalStack(cfg, widthPx)
                "grid" -> gridCard(cfg, widthPx)
                "conditional" -> conditionalCard(cfg, widthPx)
                "entity-filter" -> entityFilterCard(cfg)
                "custom:bubble-card" -> bubbleCard(cfg)
                else -> mushroomCard(cfg, widthPx) ?: fallbackCard(cfg)
            }
        } catch (e: Exception) {
            timber.log.Timber.w(e, "Could not render %s card", cfg.str("type"))
            fallbackCard(cfg)
        }
        cfg.arr("visibility")?.let { conditions -> bindVisibility(v, conditions) }
        return v
    }

    private fun tileCard(cfg: JsonObject): View {
        val id = cfg.str("entity") ?: return fallbackCard(cfg)
        val verticalLayout = cfg.bool("vertical", false)
        val card = (if (verticalLayout) vertical() else horizontal()).apply {
            gravity = if (verticalLayout) Gravity.CENTER_HORIZONTAL else Gravity.CENTER_VERTICAL
            background = glass()
            setPadding(dp(10), dp(10), dp(10), dp(10))
            minimumHeight = dp(62)
        }
        val badge = FrameLayout(context)
        val circle = iconTile(36)
        badge.background = circle
        val icon = MdiIcons.view(context, 20f, HaFormat.COLOR_INACTIVE)
        badge.addView(icon, FrameLayout.LayoutParams(dp(36), dp(36)))
        card.addView(badge, LinearLayout.LayoutParams(dp(36), dp(36)))
        val texts = vertical().apply { if (verticalLayout) gravity = Gravity.CENTER_HORIZONTAL }
        val name = text(15f, Color.WHITE)
        val state = text(13f, SECONDARY)
        texts.addView(name, wrap())
        if (!cfg.bool("hide_state", false)) texts.addView(state, wrap(top = 3))
        card.addView(texts, if (verticalLayout) wrap(top = 8) else LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(10) })
        if (verticalLayout) { name.gravity = Gravity.CENTER; state.gravity = Gravity.CENTER }

        bindEntity(id) { e ->
            name.text = displayName(cfg, e, id)
            if (e == null) {
                state.text = "—"
                return@bindEntity
            }
            val accent = accent(cfg, e)
            icon.setTextColor(accent)
            circle.setColor(if (accent == HaFormat.COLOR_INACTIVE) ICON_BG else withAlpha(accent, 0x38))
            MdiIcons.set(icon, HaFormat.iconName(e, cfg.str("icon")))
            state.text = stateContent(cfg, e)
            state.setTextColor(if (HaFormat.isAlert(e)) HaFormat.RED else SECONDARY)
            // "on" tiles glow with their colour; unavailable ones step back
            card.background = if (HaFormat.isActive(e)) glass(tint = accent) else glass()
            card.alpha = if (e.isUnavailable) 0.45f else 1f
        }
        return card
    }

    private fun entityCard(cfg: JsonObject): View {
        val id = cfg.str("entity") ?: return fallbackCard(cfg)
        val card = cardBox()
        val header = horizontal().apply { gravity = Gravity.CENTER_VERTICAL }
        val name = text(15f, SECONDARY, bold = true)
        val icon = MdiIcons.view(context, 24f, HaFormat.COLOR_INACTIVE)
        header.addView(name, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(icon, wrap())
        card.addView(header, matchWrap())
        val valueRow = horizontal().apply { gravity = Gravity.BOTTOM }
        val value = text(34f, Color.WHITE, light = true)
        val unit = text(17f, SECONDARY)
        valueRow.addView(value, wrap())
        valueRow.addView(unit, wrap(left = 6, bottom = 4))
        card.addView(valueRow, wrap(top = 8))
        bindEntity(id) { e ->
            name.text = displayName(cfg, e, id)
            if (e == null) { value.text = "—"; unit.text = ""; return@bindEntity }
            MdiIcons.set(icon, HaFormat.iconName(e, cfg.str("icon")))
            icon.setTextColor(accent(cfg, e))
            val attribute = cfg.str("attribute")
            if (attribute != null) {
                value.text = e.attr(attribute)?.let { formatValue(it) } ?: "—"
                unit.text = cfg.str("unit").orEmpty()
            } else {
                val (v, u) = valueAndUnit(e)
                value.text = v
                unit.text = cfg.str("unit") ?: u
            }
        }
        return card
    }

    private fun entitiesCard(cfg: JsonObject): View {
        val card = cardBox()
        cardTitle(cfg)?.let { card.addView(it, matchWrap(bottom = 6)) }
        cfg.arr("entities")?.forEach { row -> entityRow(row)?.let { card.addView(it, matchWrap()) } }
        return card
    }

    private fun entityRow(row: JsonElement): View? {
        val cfg = row.asObj() ?: (if (row.isJsonPrimitive) JsonObject().apply { addProperty("entity", row.asString) } else return null)
        when (cfg.str("type")) {
            "section" -> return text(12f, LABEL, bold = true, caps = true).apply {
                text = cfg.str("label").orEmpty()
                setPadding(0, dp(12), 0, dp(2))
            }
            "divider" -> return View(context).apply {
                setBackgroundColor(0x1FFFFFFF)
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply {
                    topMargin = dp(6); bottomMargin = dp(6)
                }
            }
            "conditional" -> {
                val inner = cfg.get("row")?.let { entityRow(it) } ?: return null
                cfg.arr("conditions")?.let { bindVisibility(inner, it) }
                return inner
            }
            "attribute" -> {
                val id = cfg.str("entity") ?: return null
                val attribute = cfg.str("attribute") ?: return null
                return rowView { icon, name, state ->
                    bindEntity(id) { e ->
                        name.text = cfg.str("name") ?: e?.friendlyName ?: id
                        if (e != null) {
                            MdiIcons.set(icon, HaFormat.iconName(e, cfg.str("icon")))
                            icon.setTextColor(HaFormat.stateColor(e))
                        }
                        state.text = cfg.str("prefix").orEmpty() + (e?.attr(attribute)?.let { formatValue(it) } ?: "—") + cfg.str("suffix").orEmpty()
                    }
                }
            }
        }
        val id = cfg.str("entity") ?: return null
        return rowView { icon, name, state ->
            val secondary = cfg.str("secondary_info")
            val sub = if (secondary == "last-changed" || secondary == "last-updated") text(12f, LABEL) else null
            sub?.let { (name.parent as ViewGroup).addView(it, wrap()) }
            bindEntity(id) { e ->
                name.text = displayName(cfg, e, id)
                if (e == null) { state.text = "—"; return@bindEntity }
                MdiIcons.set(icon, HaFormat.iconName(e, cfg.str("icon")))
                icon.setTextColor(accent(cfg, e))
                state.text = HaFormat.stateText(e)
                state.setTextColor(if (HaFormat.isActive(e) && e.domain in TOGGLES) HaFormat.stateColor(e) else SECONDARY)
                sub?.text = HaFormat.relativeTime(Instant.ofEpochMilli((e.lastChanged * 1000).toLong()), Instant.now())
            }
        }
    }

    /** icon · name (+ optional second line) · state, as in HA's entity rows. */
    private fun rowView(bindRow: (TextView, TextView, TextView) -> Unit): View {
        val row = horizontal().apply {
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(40)
        }
        val icon = MdiIcons.view(context, 22f, HaFormat.COLOR_INACTIVE)
        row.addView(icon, LinearLayout.LayoutParams(dp(30), dp(30)))
        val names = vertical()
        val name = text(15f, Color.WHITE)
        names.addView(name, wrap())
        row.addView(names, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(12) })
        val state = text(15f, SECONDARY).apply { gravity = Gravity.END }
        row.addView(state, wrap(left = 8))
        bindRow(icon, name, state)
        return row
    }

    private fun glanceCard(cfg: JsonObject): View {
        val card = cardBox()
        cardTitle(cfg)?.let { card.addView(it, matchWrap(bottom = 8)) }
        val ids = entityList(cfg.arr("entities"))
        val columns = cfg.int("columns") ?: minOf(5, max(1, ids.size))
        val showName = cfg.bool("show_name", true)
        val showIcon = cfg.bool("show_icon", true)
        val showState = cfg.bool("show_state", true)
        ids.chunked(columns).forEach { chunk ->
            val row = horizontal()
            chunk.forEach { (id, item) ->
                val cell = vertical().apply { gravity = Gravity.CENTER_HORIZONTAL; setPadding(0, dp(4), 0, dp(4)) }
                val name = text(12f, SECONDARY).apply { gravity = Gravity.CENTER }
                val icon = MdiIcons.view(context, 26f, HaFormat.COLOR_INACTIVE)
                val state = text(13f, Color.WHITE).apply { gravity = Gravity.CENTER }
                if (showName) cell.addView(name, matchWrap())
                if (showIcon) cell.addView(icon, LinearLayout.LayoutParams(dp(36), dp(36)).apply { topMargin = dp(4); bottomMargin = dp(2) })
                if (showState) cell.addView(state, matchWrap())
                row.addView(cell, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                bindEntity(id) { e ->
                    name.text = displayName(item, e, id)
                    if (e == null) { state.text = "—"; return@bindEntity }
                    MdiIcons.set(icon, HaFormat.iconName(e, item.str("icon")))
                    icon.setTextColor(accent(item, e))
                    state.text = HaFormat.stateText(e)
                }
            }
            repeat(columns - chunk.size) { row.addView(View(context), LinearLayout.LayoutParams(0, 1, 1f)) }
            card.addView(row, matchWrap())
        }
        return card
    }

    private fun sensorCard(cfg: JsonObject): View {
        val id = cfg.str("entity") ?: return fallbackCard(cfg)
        val card = entityCard(cfg) as LinearLayout
        if (cfg.str("graph") == "line") {
            val graph = SparklineView(context)
            card.addView(graph, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)).apply { topMargin = dp(8) })
            val hours = cfg.int("hours_to_show") ?: 24
            histories += HistoryRequest(listOf(id), hours) { data, start, end ->
                val color = entities[id]?.let { accentOrPrimary(cfg, it) } ?: PRIMARY
                graph.setData(listOf(SparklineView.Series(data[id].orEmpty(), color)), start, end)
            }
        }
        return card
    }

    private fun gaugeCard(cfg: JsonObject): View {
        val id = cfg.str("entity") ?: return fallbackCard(cfg)
        val card = cardBox().apply { gravity = Gravity.CENTER_HORIZONTAL }
        val frame = FrameLayout(context)
        val arc = GaugeArcView(context)
        frame.addView(arc, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val value = text(26f, Color.WHITE, light = true).apply { gravity = Gravity.CENTER }
        frame.addView(value, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        card.addView(frame, LinearLayout.LayoutParams(dp(180), ViewGroup.LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.CENTER_HORIZONTAL })
        val name = text(14f, SECONDARY).apply { gravity = Gravity.CENTER }
        card.addView(name, matchWrap(top = 6))
        val min = cfg.double("min") ?: 0.0
        val maxV = cfg.double("max") ?: 100.0
        bindEntity(id) { e ->
            name.text = displayName(cfg, e, id)
            val v = (cfg.str("attribute")?.let { e?.attrDouble(it) } ?: e?.state?.toDoubleOrNull())
            if (v == null) { value.text = "—"; arc.fraction = 0f; return@bindEntity }
            arc.fraction = ((v - min) / (maxV - min)).toFloat()
            arc.color = gaugeColor(cfg, v)
            value.text = HaFormat.withUnit(HaFormat.number(v), cfg.str("unit") ?: e?.unit)
        }
        return card
    }

    private fun gaugeColor(cfg: JsonObject, v: Double): Int {
        cfg.arr("segments")?.let { segments ->
            segments.mapNotNull { it.asObj() }
                .filter { (it.double("from") ?: Double.MAX_VALUE) <= v }
                .maxByOrNull { it.double("from") ?: 0.0 }
                ?.let { return HaFormat.namedColor(it.str("color")) ?: PRIMARY }
        }
        val severity = cfg.obj("severity") ?: return PRIMARY
        val red = severity.double("red")
        val yellow = severity.double("yellow")
        val green = severity.double("green")
        return when {
            red != null && v >= red -> 0xFFF0626B.toInt()
            yellow != null && v >= yellow -> 0xFFFFC107.toInt()
            green != null && v >= green -> 0xFF4CC38A.toInt()
            else -> PRIMARY
        }
    }

    private fun thermostatCard(cfg: JsonObject): View {
        val id = cfg.str("entity") ?: return fallbackCard(cfg)
        val card = cardBox().apply { gravity = Gravity.CENTER_HORIZONTAL }
        val name = text(15f, SECONDARY, bold = true).apply { gravity = Gravity.CENTER }
        card.addView(name, matchWrap(bottom = 6))
        val frame = FrameLayout(context)
        val arc = GaugeArcView(context)
        frame.addView(arc, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val target = text(34f, Color.WHITE, light = true).apply { gravity = Gravity.CENTER }
        frame.addView(target, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        card.addView(frame, LinearLayout.LayoutParams(dp(190), ViewGroup.LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.CENTER_HORIZONTAL })
        val detail = text(13f, SECONDARY).apply { gravity = Gravity.CENTER }
        card.addView(detail, matchWrap(top = 6))
        bindEntity(id) { e ->
            name.text = displayName(cfg, e, id)
            if (e == null) { target.text = "—"; return@bindEntity }
            val humidifier = e.domain == "humidifier"
            val unit = if (humidifier) "%" else "°"
            val lo = e.attrDouble(if (humidifier) "min_humidity" else "min_temp") ?: if (humidifier) 0.0 else 7.0
            val hi = e.attrDouble(if (humidifier) "max_humidity" else "max_temp") ?: if (humidifier) 100.0 else 35.0
            val single = e.attrDouble(if (humidifier) "humidity" else "temperature")
            val low = e.attrDouble("target_temp_low")
            val high = e.attrDouble("target_temp_high")
            arc.color = if (HaFormat.isActive(e)) HaFormat.stateColor(e) else HaFormat.COLOR_INACTIVE
            when {
                single != null -> {
                    target.text = HaFormat.number(single) + unit
                    arc.fraction = ((single - lo) / (hi - lo)).toFloat()
                }
                low != null && high != null -> {
                    target.text = "${HaFormat.number(low)}–${HaFormat.number(high)}$unit"
                    arc.fraction = ((high - lo) / (hi - lo)).toFloat()
                }
                else -> {
                    target.text = HaFormat.stateText(e)
                    arc.fraction = 0f
                }
            }
            val current = e.attrDouble(if (humidifier) "current_humidity" else "current_temperature")
            val mode = e.attrString("hvac_action")?.let { HaFormat.humanize(it) } ?: HaFormat.humanize(e.state)
            detail.text = listOfNotNull(mode, current?.let { "Currently ${HaFormat.number(it)}$unit" }).joinToString(" · ")
        }
        return card
    }

    private fun lightCard(cfg: JsonObject): View {
        val id = cfg.str("entity") ?: return fallbackCard(cfg)
        val card = cardBox().apply { gravity = Gravity.CENTER_HORIZONTAL }
        val frame = FrameLayout(context)
        val ring = RingView(context)
        frame.addView(ring, FrameLayout.LayoutParams(dp(110), dp(110)))
        val icon = MdiIcons.view(context, 40f, HaFormat.COLOR_INACTIVE)
        frame.addView(icon, FrameLayout.LayoutParams(dp(110), dp(110)))
        card.addView(frame, LinearLayout.LayoutParams(dp(110), dp(110)).apply { gravity = Gravity.CENTER_HORIZONTAL })
        val state = text(18f, Color.WHITE).apply { gravity = Gravity.CENTER }
        val name = text(14f, SECONDARY).apply { gravity = Gravity.CENTER }
        card.addView(state, matchWrap(top = 8))
        card.addView(name, matchWrap())
        bindEntity(id) { e ->
            name.text = displayName(cfg, e, id)
            if (e == null) { state.text = "—"; return@bindEntity }
            val color = accent(cfg, e)
            MdiIcons.set(icon, HaFormat.iconName(e, cfg.str("icon")))
            icon.setTextColor(color)
            ring.color = color
            ring.fraction = if (e.state == "on") ((e.attrDouble("brightness") ?: 255.0) / 255.0).toFloat() else 0f
            state.text = HaFormat.stateText(e)
        }
        return card
    }

    private fun buttonCard(cfg: JsonObject): View {
        val card = cardBox().apply { gravity = Gravity.CENTER }
        val icon = MdiIcons.view(context, 40f, HaFormat.COLOR_INACTIVE)
        val name = text(15f, Color.WHITE).apply { gravity = Gravity.CENTER }
        val state = text(13f, SECONDARY).apply { gravity = Gravity.CENTER }
        if (cfg.bool("show_icon", true)) card.addView(icon, LinearLayout.LayoutParams(dp(52), dp(52)).apply { gravity = Gravity.CENTER_HORIZONTAL })
        if (cfg.bool("show_name", true)) card.addView(name, matchWrap(top = 4))
        if (cfg.bool("show_state", false)) card.addView(state, matchWrap())
        val id = cfg.str("entity")
        if (id == null) {
            MdiIcons.set(icon, cfg.str("icon")?.let { HaFormat.stripIconPrefix(it) } ?: "gesture-tap-button")
            icon.setTextColor(HaFormat.namedColor(cfg.str("color")) ?: PRIMARY)
            name.text = cfg.str("name").orEmpty()
            return card
        }
        bindEntity(id) { e ->
            name.text = displayName(cfg, e, id)
            if (e == null) return@bindEntity
            MdiIcons.set(icon, HaFormat.iconName(e, cfg.str("icon")))
            icon.setTextColor(accent(cfg, e))
            state.text = HaFormat.stateText(e)
        }
        return card
    }

    private fun weatherCard(cfg: JsonObject): View {
        val id = cfg.str("entity") ?: return fallbackCard(cfg)
        val card = cardBox()
        if (cfg.bool("show_current", true)) {
            val top = horizontal().apply { gravity = Gravity.CENTER_VERTICAL }
            val icon = ImageView(context)
            top.addView(icon, LinearLayout.LayoutParams(dp(64), dp(64)))
            val texts = vertical()
            val condition = text(18f, Color.WHITE)
            val name = text(13f, LABEL)
            texts.addView(condition, wrap())
            texts.addView(name, wrap())
            top.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(12) })
            val temp = text(42f, Color.WHITE, light = true)
            top.addView(temp, wrap())
            card.addView(top, matchWrap())
            val details = text(13f, SECONDARY)
            card.addView(details, matchWrap(top = 8))
            bindEntity(id) { e ->
                name.text = displayName(cfg, e, id)
                if (e == null) { condition.text = "—"; return@bindEntity }
                icon.setImageDrawable(AppCompatResources.getDrawable(context, weatherDrawable(e.state)))
                condition.text = HaFormat.weatherConditionText(e.state)
                temp.text = e.attrDouble("temperature")?.let { HaFormat.number(it.roundToInt().toDouble()) + "°" } ?: ""
                details.text = listOfNotNull(
                    e.attrDouble("humidity")?.let { "Humidity ${HaFormat.number(it)}%" },
                    e.attrDouble("wind_speed")?.let { "Wind ${HaFormat.number(it.roundToInt().toDouble())} ${e.attrString("wind_speed_unit") ?: "km/h"}" },
                    e.attrDouble("apparent_temperature")?.let { "Feels like ${it.roundToInt()}°" }
                ).joinToString("  ·  ")
                details.visibility = if (details.text.isEmpty()) View.GONE else View.VISIBLE
            }
        }
        if (cfg.bool("show_forecast", true)) {
            val row = horizontal()
            card.addView(row, matchWrap(top = 10))
            val type = cfg.str("forecast_type") ?: "daily"
            forecasts += ForecastRequest(id, type) { forecast -> fillForecast(row, forecast, type) }
        }
        return card
    }

    private fun fillForecast(row: LinearLayout, forecast: JsonArray, type: String) {
        row.removeAllViews()
        val hourly = type == "hourly"
        forecast.take(5).mapNotNull { it.asObj() }.forEach { f ->
            val cell = vertical().apply { gravity = Gravity.CENTER_HORIZONTAL }
            val time = f.str("datetime")?.let { runCatching { OffsetDateTime.parse(it) }.getOrNull() }
                ?.atZoneSameInstant(ZoneId.systemDefault())
            val label = text(13f, SECONDARY).apply {
                gravity = Gravity.CENTER
                text = time?.format(DateTimeFormatter.ofPattern(if (hourly) "h a" else "EEE", Locale.getDefault())) ?: ""
            }
            cell.addView(label, matchWrap())
            val icon = ImageView(context).apply {
                setImageDrawable(AppCompatResources.getDrawable(context, weatherDrawable(f.str("condition") ?: "cloudy")))
            }
            cell.addView(icon, LinearLayout.LayoutParams(dp(32), dp(32)).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(2) })
            val hi = f.double("temperature")?.let { HaFormat.number(it.roundToInt().toDouble()) + "°" } ?: ""
            val lo = f.double("templow")?.let { HaFormat.number(it.roundToInt().toDouble()) + "°" }
            cell.addView(text(14f, Color.WHITE).apply { gravity = Gravity.CENTER; text = hi }, matchWrap(top = 2))
            lo?.let { cell.addView(text(12f, SECONDARY).apply { gravity = Gravity.CENTER; text = it }, matchWrap()) }
            row.addView(cell, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
    }

    private fun weatherDrawable(condition: String): Int = when (condition) {
        "sunny" -> R.drawable.ic_weather_sun
        "clear-night" -> R.drawable.ic_weather_moon
        "partlycloudy" -> R.drawable.ic_weather_partly
        "rainy", "pouring" -> R.drawable.ic_weather_rain
        "snowy", "snowy-rainy", "hail" -> R.drawable.ic_weather_snow
        "lightning", "lightning-rainy" -> R.drawable.ic_weather_thunder
        else -> R.drawable.ic_weather_cloud
    }

    private fun markdownCard(cfg: JsonObject): View {
        val card = cardBox()
        cardTitle(cfg)?.let { card.addView(it, matchWrap(bottom = 6)) }
        val body = text(15f, Color.WHITE).apply {
            setSingleLine(false)
            maxLines = Int.MAX_VALUE
            ellipsize = null
            setLineSpacing(0f, 1.15f)
        }
        card.addView(body, matchWrap())
        val content = cfg.str("content").orEmpty()
        if (content.contains("{{") || content.contains("{%")) {
            body.text = ""
            templates += TemplateRequest(content) { rendered -> body.text = markdown(rendered) }
        } else {
            body.text = markdown(content)
        }
        return card
    }

    private fun headingCard(cfg: JsonObject): View {
        val row = horizontal().apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(8), dp(4), dp(2))
        }
        cfg.str("icon")?.let { HaFormat.stripIconPrefix(it) }?.let { name ->
            val icon = MdiIcons.view(context, 16f, LABEL)
            MdiIcons.set(icon, name)
            row.addView(icon, LinearLayout.LayoutParams(dp(20), dp(20)).apply { rightMargin = dp(6) })
        }
        val subtitle = cfg.str("heading_style") == "subtitle"
        val title = text(if (subtitle) 12f else 13f, LABEL, bold = true, caps = true).apply {
            text = cfg.str("heading").orEmpty()
        }
        row.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        cfg.arr("badges")?.let { badges ->
            entityList(badges).forEach { (id, item) -> row.addView(chip(id, item, compact = true), wrap(left = 8)) }
        }
        return row
    }

    private fun pictureCard(cfg: JsonObject, widthPx: Int): View {
        val image = pictureView(cfg, widthPx)
        cfg.str("image")?.let { images.load(it, image, widthPx) }
        return roundedFrame(image)
    }

    private fun pictureEntityCard(cfg: JsonObject, widthPx: Int): View {
        val id = cfg.str("entity") ?: return pictureCard(cfg, widthPx)
        val frame = FrameLayout(context).apply { background = glass() }
        val image = pictureView(cfg, widthPx)
        frame.addView(image)
        val footer = horizontal().apply {
            setBackgroundColor(0x99000000.toInt())
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        val name = text(14f, Color.WHITE, bold = true)
        val state = text(14f, SECONDARY)
        if (cfg.bool("show_name", true)) footer.addView(name, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (cfg.bool("show_state", true)) footer.addView(state, wrap())
        frame.addView(footer, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        val staticImage = cfg.str("image")
        bindEntity(id) { e ->
            name.text = displayName(cfg, e, id)
            state.text = e?.let { HaFormat.stateText(it) } ?: "—"
            if (staticImage == null) e?.attrString("entity_picture")?.let { images.load(it, image, widthPx) }
        }
        staticImage?.let { images.load(it, image, widthPx) }
        if (id.startsWith("camera.") && staticImage == null) {
            periodic += { entities[id]?.attrString("entity_picture")?.let { images.load(cacheBust(it), image, widthPx) } }
        }
        return frame
    }

    private fun pictureGlanceCard(cfg: JsonObject, widthPx: Int): View {
        val frame = FrameLayout(context).apply { background = glass() }
        val image = pictureView(cfg, widthPx)
        frame.addView(image)
        val bar = horizontal().apply {
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0x99000000.toInt())
            setPadding(dp(12), dp(6), dp(8), dp(6))
        }
        bar.addView(text(15f, Color.WHITE, bold = true).apply { text = cfg.str("title").orEmpty() },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        entityList(cfg.arr("entities")).forEach { (id, item) ->
            val icon = MdiIcons.view(context, 22f, HaFormat.COLOR_INACTIVE)
            bar.addView(icon, LinearLayout.LayoutParams(dp(32), dp(32)))
            bindEntity(id) { e ->
                if (e == null) return@bindEntity
                MdiIcons.set(icon, HaFormat.iconName(e, item.str("icon")))
                icon.setTextColor(if (HaFormat.isActive(e)) HaFormat.stateColor(e) else Color.WHITE)
            }
        }
        frame.addView(bar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        val camera = cfg.str("camera_image")
        when {
            camera != null -> {
                bindEntity(camera) { e -> e?.attrString("entity_picture")?.let { images.load(it, image, widthPx) } }
                periodic += { entities[camera]?.attrString("entity_picture")?.let { images.load(cacheBust(it), image, widthPx) } }
            }
            cfg.str("image") != null -> images.load(cfg.str("image")!!, image, widthPx)
        }
        return frame
    }

    private fun mediaCard(cfg: JsonObject, widthPx: Int): View {
        val id = cfg.str("entity") ?: return fallbackCard(cfg)
        val card = horizontal().apply {
            background = glass()
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(12), dp(14), dp(12))
        }
        val art = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        val icon = MdiIcons.view(context, 20f, HaFormat.COLOR_INACTIVE)
        val iconShape = GradientDrawable().apply { shape = GradientDrawable.OVAL }
        val artBox = FrameLayout(context)
        artBox.addView(icon, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        artBox.addView(art, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        card.addView(artBox, LinearLayout.LayoutParams(dp(36), dp(36)))
        val texts = vertical()
        val title = text(15f, Color.WHITE)
        val artist = text(13f, SECONDARY)
        val status = text(12f, LABEL)
        texts.addView(title, wrap())
        texts.addView(artist, wrap())
        texts.addView(status, wrap(top = 4))
        card.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(12) })
        bindEntity(id) { e ->
            if (e == null) { title.text = id; return@bindEntity }
            val playing = e.state == "playing" || e.state == "paused"
            title.text = (if (playing) e.attrString("media_title") else null) ?: displayName(cfg, e, id)
            artist.text = if (playing) listOfNotNull(e.attrString("media_artist"), e.attrString("media_album_name")).joinToString(" · ") else ""
            status.text = listOfNotNull(HaFormat.stateText(e), e.attrString("app_name"), if (playing) e.friendlyName else null)
                .distinct().joinToString(" · ")
            status.setTextColor(if (playing) LABEL else SECONDARY)
            MdiIcons.set(icon, HaFormat.iconName(e, cfg.str("icon")))
            val accent = HaFormat.iconColor(e)
            icon.setTextColor(accent)
            iconShape.setColor(withAlpha(accent, if (accent == HaFormat.COLOR_INACTIVE) 0x1F else 0x38))
            val picture = if (playing) e.attrString("entity_picture") else null
            // playing/paused: large artwork, tinted card. Otherwise a compact tile-like row.
            val size = if (playing) dp(84) else dp(36)
            artBox.layoutParams = LinearLayout.LayoutParams(size, size)
            artBox.background = if (playing) GradientDrawable().apply { setColor(0x1AFFFFFF); cornerRadius = dp(12).toFloat() } else iconShape
            icon.setTextSize(TypedValue.COMPLEX_UNIT_DIP, if (playing) 36f else 20f)
            artist.visibility = if (playing && artist.text.isNotEmpty()) View.VISIBLE else View.GONE
            card.setPadding(dp(10), dp(10), dp(12), dp(10))
            card.background = if (e.state == "playing") glass(tint = HaFormat.stateColor(e)) else glass()
            card.alpha = if (e.isUnavailable) 0.45f else 1f
            if (picture != null) {
                art.visibility = View.VISIBLE
                if (art.getTag(R.id.ha_image_path) != picture) images.load(picture, art, dp(84))
            } else {
                art.visibility = View.GONE
                art.setTag(R.id.ha_image_path, null)
            }
        }
        return card
    }

    private fun historyCard(cfg: JsonObject): View {
        val card = cardBox()
        cardTitle(cfg)?.let { card.addView(it, matchWrap(bottom = 8)) }
        val ids = entityList(cfg.arr("entities"))
        val graph = SparklineView(context)
        card.addView(graph, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(110)))
        val legend = vertical()
        card.addView(legend, matchWrap(top = 6))
        ids.forEachIndexed { i, (id, item) ->
            val color = SERIES[i % SERIES.size]
            val row = horizontal().apply { gravity = Gravity.CENTER_VERTICAL }
            row.addView(View(context).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) } },
                LinearLayout.LayoutParams(dp(8), dp(8)))
            val name = text(13f, SECONDARY)
            val state = text(13f, Color.WHITE)
            row.addView(name, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(8) })
            row.addView(state, wrap())
            legend.addView(row, matchWrap(top = 2))
            bindEntity(id) { e ->
                name.text = displayName(item, e, id)
                state.text = e?.let { HaFormat.stateText(it) } ?: "—"
            }
        }
        val hours = cfg.int("hours_to_show") ?: ((cfg.int("days_to_show") ?: 1) * 24)
        histories += HistoryRequest(ids.map { it.first }, hours.coerceIn(1, 24 * 7)) { data, start, end ->
            graph.setData(ids.mapIndexed { i, (id, _) -> SparklineView.Series(data[id].orEmpty(), SERIES[i % SERIES.size]) }, start, end)
        }
        return card
    }

    private fun verticalStack(cfg: JsonObject, widthPx: Int): View {
        val box = vertical()
        cardTitle(cfg)?.let { box.addView(it, matchWrap(bottom = 6)) }
        cfg.arr("cards")?.mapNotNull { it.asObj() }?.forEachIndexed { i, child ->
            box.addView(card(child, widthPx), matchWrap(top = if (i > 0) 8 else 0))
        }
        return box
    }

    private fun horizontalStack(cfg: JsonObject, widthPx: Int): View {
        val children = cfg.arr("cards")?.mapNotNull { it.asObj() }.orEmpty()
        val box = vertical()
        cardTitle(cfg)?.let { box.addView(it, matchWrap(bottom = 6)) }
        val row = horizontal()
        val gap = dp(8)
        val childWidth = if (children.isEmpty()) widthPx else (widthPx - gap * (children.size - 1)) / children.size
        children.forEachIndexed { i, child ->
            row.addView(card(child, childWidth), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
                if (i > 0) leftMargin = gap
            })
        }
        box.addView(row, matchWrap())
        return box
    }

    private fun gridCard(cfg: JsonObject, widthPx: Int): View {
        val children = cfg.arr("cards")?.mapNotNull { it.asObj() }.orEmpty()
        val columns = cfg.int("columns") ?: 3
        val box = vertical()
        cardTitle(cfg)?.let { box.addView(it, matchWrap(bottom = 6)) }
        val gap = dp(8)
        val childWidth = (widthPx - gap * (columns - 1)) / columns
        children.chunked(columns).forEachIndexed { r, chunk ->
            val row = horizontal()
            chunk.forEachIndexed { i, child ->
                row.addView(card(child, childWidth), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
                    if (i > 0) leftMargin = gap
                })
            }
            repeat(columns - chunk.size) { row.addView(View(context), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply { leftMargin = gap }) }
            box.addView(row, matchWrap(top = if (r > 0) 8 else 0))
        }
        return box
    }

    private fun conditionalCard(cfg: JsonObject, widthPx: Int): View {
        val inner = cfg.obj("card")?.let { card(it, widthPx) } ?: return View(context)
        cfg.arr("conditions")?.let { bindVisibility(inner, it) }
        return inner
    }

    private fun entityFilterCard(cfg: JsonObject): View {
        val filters = cfg.arr("state_filter")
        val card = cardBox()
        cfg.obj("card")?.str("title")?.let { title -> card.addView(cardTitle(JsonObject().apply { addProperty("title", title) })!!, matchWrap(bottom = 6)) }
        entityList(cfg.arr("entities")).forEach { (id, item) ->
            val row = entityRow(item) ?: return@forEach
            card.addView(row, matchWrap())
            bindEntity(id) { e -> row.visibility = if (e != null && matchesFilter(e, filters)) View.VISIBLE else View.GONE }
        }
        return card
    }

    private fun matchesFilter(e: HaEntity, filters: JsonArray?): Boolean {
        if (filters == null || filters.size() == 0) return true
        return filters.any { f ->
            when {
                f.isJsonPrimitive -> e.state == f.asString
                f.isJsonObject -> {
                    val o = f.asJsonObject
                    val value = o.str("value") ?: return@any false
                    val v = e.state.toDoubleOrNull()
                    val target = value.toDoubleOrNull()
                    when (o.str("operator") ?: "==") {
                        "==" -> e.state == value
                        "!=" -> e.state != value
                        ">" -> v != null && target != null && v > target
                        ">=" -> v != null && target != null && v >= target
                        "<" -> v != null && target != null && v < target
                        "<=" -> v != null && target != null && v <= target
                        else -> false
                    }
                }
                else -> false
            }
        }
    }

    /** Unsupported card: list whatever entities it references, so it's still informative. */
    private fun fallbackCard(cfg: JsonObject): View {
        val ids = LinkedHashSet<String>()
        collectEntities(cfg, ids)
        if (ids.isEmpty()) {
            return cardBox().apply {
                addView(text(13f, LABEL).apply { text = "${cfg.str("type") ?: "This"} card isn't shown on the frame" }, matchWrap())
            }
        }
        val synthetic = JsonObject().apply {
            cfg.str("title")?.let { addProperty("title", it) }
            add("entities", JsonArray().apply { ids.forEach { add(it) } })
        }
        return entitiesCard(synthetic)
    }

    private fun collectEntities(element: JsonElement, out: MutableSet<String>) {
        when {
            element.isJsonObject -> element.asJsonObject.entrySet().forEach { (k, v) ->
                if ((k == "entity" || k == "camera_image") && v.isJsonPrimitive && v.asString.contains('.')) out += v.asString
                else if (k == "entities" && v.isJsonArray) v.asJsonArray.forEach { item ->
                    if (item.isJsonPrimitive && item.asString.contains('.')) out += item.asString else collectEntities(item, out)
                } else if (v.isJsonObject || v.isJsonArray) collectEntities(v, out)
            }
            element.isJsonArray -> element.asJsonArray.forEach { collectEntities(it, out) }
        }
    }

    // ---- Mushroom (custom:mushroom-*) ---------------------------------------------------------
    // The frame can't run Mushroom's JavaScript, so its cards are drawn natively in the same
    // visual language: a tinted circular icon "shape", a primary line and a secondary line.

    private fun mushroomCard(cfg: JsonObject, widthPx: Int): View? {
        val type = cfg.str("type")?.takeIf { it.startsWith("custom:mushroom-") } ?: return null
        return when (type.removePrefix("custom:mushroom-").removeSuffix("-card")) {
            "template" -> mushroomTemplateCard(cfg)
            "title" -> mushroomTitleCard(cfg)
            "chips" -> mushroomChipsCard(cfg)
            "media-player" -> mediaCard(cfg, widthPx)
            "empty" -> View(context)
            else -> mushroomEntityCard(cfg) // entity, light, fan, cover, climate, lock, person, ...
        }
    }

    /** Mushroom's icon "shape": a tinted circle holding an icon or a round entity picture. */
    private inner class MushroomShape(val sizeDp: Int) {
        val view = FrameLayout(context)
        private val background = iconTile(sizeDp)
        private val icon = MdiIcons.view(context, sizeDp * 0.55f, HaFormat.COLOR_INACTIVE)
        private val picture = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            visibility = View.GONE
        }

        init {
            view.background = background
            view.addView(icon, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            view.addView(picture, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }

        fun color(c: Int) {
            icon.setTextColor(c)
            background.setColor(if (c == HaFormat.COLOR_INACTIVE) ICON_BG else withAlpha(c, 0x38))
        }

        fun icon(name: String?) {
            picture.visibility = View.GONE
            icon.visibility = View.VISIBLE
            MdiIcons.set(icon, name)
        }

        fun picture(path: String) {
            icon.visibility = View.GONE
            picture.visibility = View.VISIBLE
            if (picture.getTag(R.id.ha_image_path) != path) images.load(path, picture, dp(sizeDp), circle = true)
        }
    }

    private class MushroomParts(val card: LinearLayout, val shape: MushroomShape, val primary: TextView, val secondary: TextView)

    private fun mushroomBase(verticalLayout: Boolean): MushroomParts {
        val card = (if (verticalLayout) vertical() else horizontal()).apply {
            gravity = if (verticalLayout) Gravity.CENTER_HORIZONTAL else Gravity.CENTER_VERTICAL
            background = glass()
            setPadding(dp(10), dp(10), dp(10), dp(10))
            minimumHeight = dp(62)
        }
        val shape = MushroomShape(if (verticalLayout) 42 else 38)
        card.addView(shape.view, LinearLayout.LayoutParams(dp(shape.sizeDp), dp(shape.sizeDp)))
        val texts = vertical().apply { if (verticalLayout) gravity = Gravity.CENTER_HORIZONTAL }
        val primary = text(14f, Color.WHITE, bold = true)
        val secondary = text(13f, SECONDARY)
        if (verticalLayout) {
            primary.gravity = Gravity.CENTER
            secondary.gravity = Gravity.CENTER
        }
        texts.addView(primary, wrap())
        texts.addView(secondary, wrap(top = 3))
        card.addView(texts, if (verticalLayout) wrap(top = 8)
            else LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(10) })
        return MushroomParts(card, shape, primary, secondary)
    }

    private fun mushroomEntityCard(cfg: JsonObject): View {
        val id = cfg.str("entity") ?: return fallbackCard(cfg)
        val parts = mushroomBase(cfg.str("layout") == "vertical")
        val iconType = cfg.str("icon_type") ?: "icon"
        if (iconType == "none") parts.shape.view.visibility = View.GONE
        bindEntity(id) { e ->
            showInfo(parts.primary, Color.WHITE, cfg.str("primary_info") ?: "name", cfg, e, id)
            showInfo(parts.secondary, SECONDARY, cfg.str("secondary_info") ?: "state", cfg, e, id)
            if (e == null) return@bindEntity
            val custom = HaFormat.namedColor(cfg.str("icon_color"))
            val color = when {
                e.isUnavailable -> HaFormat.COLOR_INACTIVE
                // like Mushroom, a configured icon_color shows whenever the entity isn't "off"
                custom != null && e.state != "off" -> custom
                else -> accent(cfg, e)
            }
            parts.shape.color(color)
            val picture = e.attrString("entity_picture")
            if (iconType == "entity-picture" && picture != null) parts.shape.picture(picture)
            else parts.shape.icon(HaFormat.iconName(e, cfg.str("icon")))
            if (HaFormat.isAlert(e)) parts.secondary.setTextColor(HaFormat.RED)
            parts.card.background = if (HaFormat.isActive(e)) glass(tint = color) else glass()
            parts.card.alpha = if (e.isUnavailable) 0.45f else 1f
        }
        return parts.card
    }

    /** primary_info / secondary_info: name, state, last-changed, last-updated, none. */
    private fun showInfo(view: TextView, color: Int, kind: String, cfg: JsonObject, e: HaEntity?, id: String) {
        view.setTextColor(color)
        val text = when (kind) {
            "name" -> displayName(cfg, e, id)
            "state" -> e?.let { HaFormat.stateText(it) } ?: "—"
            "last-changed", "last-updated" -> e?.let {
                HaFormat.relativeTime(Instant.ofEpochMilli((it.lastChanged * 1000).toLong()), Instant.now())
            } ?: ""
            else -> ""
        }
        view.text = text
        view.visibility = if (kind == "none" || text.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun isTemplate(value: String) = value.contains("{{") || value.contains("{%")

    /** Sets [apply] from a plain value, or from Home Assistant once it renders the template. */
    private fun templated(cfg: JsonObject, key: String, apply: (String) -> Unit) {
        val value = cfg.str(key) ?: return apply("")
        if (!isTemplate(value)) return apply(value)
        apply("")
        val variables = JsonObject().apply { cfg.str("entity")?.let { addProperty("entity", it) } }
        templates += TemplateRequest(value, variables) { apply(it.trim()) }
    }

    private fun mushroomTemplateCard(cfg: JsonObject): View {
        val parts = mushroomBase(cfg.str("layout") == "vertical")
        if (cfg.bool("multiline_secondary", false)) {
            parts.secondary.setSingleLine(false)
            parts.secondary.maxLines = 4
        }
        templated(cfg, "primary") { parts.primary.text = it; parts.primary.visibility = if (it.isEmpty()) View.GONE else View.VISIBLE }
        templated(cfg, "secondary") { parts.secondary.text = it; parts.secondary.visibility = if (it.isEmpty()) View.GONE else View.VISIBLE }
        var hasIcon = false
        templated(cfg, "icon_color") { parts.shape.color(HaFormat.namedColor(it) ?: HaFormat.COLOR_INACTIVE) }
        templated(cfg, "icon") { icon ->
            hasIcon = icon.isNotEmpty()
            HaFormat.stripIconPrefix(icon)?.let { parts.shape.icon(it) }
            parts.shape.view.visibility = if (hasIcon || cfg.str("picture") != null) View.VISIBLE else View.GONE
        }
        templated(cfg, "picture") { if (it.isNotEmpty()) { parts.shape.picture(it); parts.shape.view.visibility = View.VISIBLE } }
        return parts.card
    }

    private fun mushroomTitleCard(cfg: JsonObject): View {
        val box = vertical().apply { setPadding(dp(4), dp(10), dp(4), dp(4)) }
        val gravity = when (cfg.str("alignment")) {
            "center" -> Gravity.CENTER
            "end" -> Gravity.END
            else -> Gravity.START
        }
        val title = text(22f, Color.WHITE, bold = true).apply { this.gravity = gravity }
        val subtitle = text(15f, SECONDARY).apply { this.gravity = gravity }
        box.addView(title, matchWrap())
        box.addView(subtitle, matchWrap(top = 4))
        templated(cfg, "title") { title.text = it; title.visibility = if (it.isEmpty()) View.GONE else View.VISIBLE }
        templated(cfg, "subtitle") { subtitle.text = it; subtitle.visibility = if (it.isEmpty()) View.GONE else View.VISIBLE }
        return box
    }

    private fun mushroomChipsCard(cfg: JsonObject): View {
        val flow = FlowLayout(context, dp(8), dp(8)).apply {
            gravityCenter = cfg.str("alignment") == "center"
            gravityEnd = cfg.str("alignment") == "end"
        }
        // spacers only make sense on a single line; in a wrapping row they're dropped
        cfg.arr("chips")?.mapNotNull { it.asObj() }?.filter { it.str("type") != "spacer" }?.forEach { chipCfg ->
            mushroomChip(chipCfg)?.let { flow.addView(it) }
        }
        return flow
    }

    private fun mushroomChip(cfg: JsonObject): View? {
        val type = cfg.str("type") ?: "entity"
        if (type == "conditional") {
            val inner = cfg.obj("chip")?.let { mushroomChip(it) } ?: return null
            cfg.arr("conditions")?.let { bindVisibility(inner, it) }
            return inner
        }
        val chip = horizontal().apply {
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dp(18).toFloat()
                setColor(0x1FFFFFFF)
                setStroke(dp(1), 0x17FFFFFF)
            }
            setPadding(dp(10), dp(6), dp(12), dp(6))
        }
        val icon = MdiIcons.view(context, 18f, HaFormat.COLOR_INACTIVE)
        val image = ImageView(context).apply { visibility = View.GONE }
        val label = text(14f, Color.WHITE)
        chip.addView(icon, LinearLayout.LayoutParams(dp(22), dp(22)))
        chip.addView(image, LinearLayout.LayoutParams(dp(22), dp(22)))
        chip.addView(label, wrap(left = 6))
        fun setLabel(value: String) {
            label.text = value
            label.visibility = if (value.isEmpty()) View.GONE else View.VISIBLE
        }
        when (type) {
            "entity", "light", "alarm-control-panel" -> {
                val id = cfg.str("entity") ?: return null
                bindEntity(id) { e ->
                    if (e == null) { setLabel("—"); return@bindEntity }
                    MdiIcons.set(icon, HaFormat.iconName(e, cfg.str("icon")))
                    val custom = HaFormat.namedColor(cfg.str("icon_color"))
                    icon.setTextColor(if (custom != null && !e.isUnavailable && e.state != "off") custom else accent(cfg, e))
                    setLabel(when (cfg.str("content_info") ?: "state") {
                        "name" -> displayName(cfg, e, id)
                        "last-changed", "last-updated" -> HaFormat.relativeTime(Instant.ofEpochMilli((e.lastChanged * 1000).toLong()), Instant.now())
                        "none" -> ""
                        else -> HaFormat.stateText(e)
                    })
                }
            }
            "weather" -> {
                val id = cfg.str("entity") ?: return null
                icon.visibility = View.GONE
                image.visibility = View.VISIBLE
                val showTemperature = cfg.bool("show_temperature", true)
                val showConditions = cfg.bool("show_conditions", false)
                bindEntity(id) { e ->
                    if (e == null) return@bindEntity
                    image.setImageDrawable(AppCompatResources.getDrawable(context, weatherDrawable(e.state)))
                    setLabel(listOfNotNull(
                        if (showTemperature) e.attrDouble("temperature")?.let { "${it.roundToInt()}°" } else null,
                        if (showConditions) HaFormat.weatherConditionText(e.state) else null
                    ).joinToString(" · "))
                }
            }
            "template" -> {
                templated(cfg, "icon") { name -> HaFormat.stripIconPrefix(name)?.let { MdiIcons.set(icon, it) }; icon.visibility = if (name.isEmpty()) View.GONE else View.VISIBLE }
                templated(cfg, "icon_color") { icon.setTextColor(HaFormat.namedColor(it) ?: HaFormat.COLOR_INACTIVE) }
                templated(cfg, "content") { setLabel(it) }
            }
            "action" -> {
                MdiIcons.set(icon, cfg.str("icon")?.let { HaFormat.stripIconPrefix(it) } ?: "flash")
                icon.setTextColor(HaFormat.namedColor(cfg.str("icon_color")) ?: HaFormat.COLOR_INACTIVE)
                setLabel("")
            }
            else -> return null // back / menu / quickbar: navigation, meaningless on the frame
        }
        return chip
    }

    // ---- Bubble Card (custom:bubble-card) ----------------------------------------------------
    // Pill-shaped buttons with a round icon; lights fill like a slider, switches tint when on.

    private fun bubbleCard(cfg: JsonObject): View = when (cfg.str("card_type")) {
        "separator" -> bubbleSeparator(cfg)
        "button", "select", "climate", "cover" -> bubbleButton(cfg)
        "media-player" -> bubbleMedia(cfg)
        "horizontal-buttons-stack" -> bubbleButtonsStack(cfg)
        "empty-column" -> View(context)
        // pop-ups open from a link; nothing to show on a wall display
        "pop-up" -> View(context).apply { visibility = View.GONE }
        else -> fallbackCard(cfg)
    }

    private class BubbleParts(val pill: BubblePill, val shape: MushroomShape, val name: TextView, val state: TextView, val subs: LinearLayout)

    private fun bubbleBase(): BubbleParts {
        val pill = BubblePill(context)
        val row = horizontal().apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(7), dp(7), dp(14), dp(7))
        }
        val shape = MushroomShape(40)
        row.addView(shape.view, LinearLayout.LayoutParams(dp(40), dp(40)))
        val texts = vertical()
        val name = text(14f, Color.WHITE, bold = true)
        val state = text(12f, SECONDARY)
        texts.addView(name, wrap())
        texts.addView(state, wrap(top = 2))
        row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(10) })
        val subs = horizontal().apply { gravity = Gravity.CENTER_VERTICAL }
        row.addView(subs, wrap(left = 6))
        pill.addView(row, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_VERTICAL))
        pill.minimumHeight = dp(54)
        return BubbleParts(pill, shape, name, state, subs)
    }

    private fun bubbleButton(cfg: JsonObject): View {
        val parts = bubbleBase()
        val type = cfg.str("card_type") ?: "button"
        val buttonType = cfg.str("button_type") ?: "switch"
        val showName = cfg.bool("show_name", true)
        val showState = cfg.bool("show_state", type != "button" || buttonType == "state")
        if (!cfg.bool("show_icon", true)) parts.shape.view.visibility = View.GONE
        cfg.arr("sub_button")?.mapNotNull { it.asObj() }?.forEach { sub -> bubbleSubButton(sub)?.let { parts.subs.addView(it, wrap(left = 6)) } }
        val id = cfg.str("entity")
        if (id == null) {
            parts.name.text = cfg.str("name").orEmpty()
            parts.state.visibility = View.GONE
            parts.shape.icon(cfg.str("icon")?.let { HaFormat.stripIconPrefix(it) } ?: "gesture-tap-button")
            parts.shape.color(PRIMARY)
            return parts.pill
        }
        bindEntity(id) { e ->
            parts.name.text = displayName(cfg, e, id)
            parts.name.visibility = if (showName) View.VISIBLE else View.GONE
            if (e == null) { parts.state.text = "—"; return@bindEntity }
            val color = accent(cfg, e)
            parts.shape.color(color)
            parts.shape.icon(HaFormat.iconName(e, cfg.str("icon")))
            val lines = mutableListOf<String>()
            if (showState) lines += when (type) {
                "select" -> e.state
                else -> HaFormat.stateText(e)
            }
            if (cfg.bool("show_last_changed", false)) {
                lines += HaFormat.relativeTime(Instant.ofEpochMilli((e.lastChanged * 1000).toLong()), Instant.now())
            }
            if (cfg.bool("show_attribute", false)) cfg.str("attribute")?.let { a -> e.attr(a)?.let { lines += formatValue(it) } }
            parts.state.text = lines.joinToString(" · ")
            parts.state.visibility = if (lines.isEmpty()) View.GONE else View.VISIBLE
            parts.state.setTextColor(if (HaFormat.isAlert(e)) HaFormat.RED else SECONDARY)
            // slider: fill to the brightness/position; switch: the whole pill tints when on
            val active = HaFormat.isActive(e)
            parts.pill.fillColor = withAlpha(color, 0x47)
            parts.pill.fraction = when {
                !active -> 0f
                buttonType == "slider" && e.domain == "light" -> ((e.attrDouble("brightness") ?: 255.0) / 255.0).toFloat()
                buttonType == "slider" && e.attrDouble("current_position") != null -> (e.attrDouble("current_position")!! / 100.0).toFloat()
                buttonType == "slider" -> e.state.toDoubleOrNull()?.let { (it / 100.0).toFloat() } ?: 1f
                buttonType == "switch" || buttonType == "slider" -> 1f
                else -> 0f
            }
            parts.pill.alpha = if (e.isUnavailable) 0.45f else 1f
        }
        return parts.pill
    }

    private fun bubbleMedia(cfg: JsonObject): View {
        val id = cfg.str("entity") ?: return fallbackCard(cfg)
        val parts = bubbleBase()
        bindEntity(id) { e ->
            if (e == null) { parts.name.text = displayName(cfg, e, id); parts.state.text = "—"; return@bindEntity }
            val playing = e.state == "playing" || e.state == "paused"
            val title = if (playing) e.attrString("media_title") else null
            parts.name.text = title ?: displayName(cfg, e, id)
            parts.state.text = if (playing) {
                listOfNotNull(e.attrString("media_artist"), cfg.str("name") ?: e.friendlyName).joinToString(" · ")
            } else HaFormat.stateText(e)
            val color = HaFormat.iconColor(e)
            parts.shape.color(color)
            val picture = if (playing) e.attrString("entity_picture") else null
            if (picture != null) parts.shape.picture(picture) else parts.shape.icon(HaFormat.iconName(e, cfg.str("icon")))
            parts.pill.fillColor = withAlpha(HaFormat.stateColor(e), 0x33)
            parts.pill.fraction = if (e.state == "playing") 1f else 0f
            parts.pill.alpha = if (e.isUnavailable) 0.45f else 1f
        }
        return parts.pill
    }

    private fun bubbleSeparator(cfg: JsonObject): View {
        val row = horizontal().apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(8), dp(2), dp(4))
            minimumHeight = dp(40)
        }
        cfg.str("icon")?.let { HaFormat.stripIconPrefix(it) }?.let { name ->
            val icon = MdiIcons.view(context, 18f, 0xD9FFFFFF.toInt())
            MdiIcons.set(icon, name)
            row.addView(icon, LinearLayout.LayoutParams(dp(24), dp(24)).apply { rightMargin = dp(8) })
        }
        row.addView(text(15f, Color.WHITE, bold = true).apply { text = cfg.str("name").orEmpty() }, wrap())
        row.addView(View(context).apply {
            background = GradientDrawable().apply { cornerRadius = dp(1).toFloat(); setColor(0x29EAEBEE) }
        }, LinearLayout.LayoutParams(0, dp(2), 1f).apply { leftMargin = dp(12); rightMargin = dp(4) })
        cfg.arr("sub_button")?.mapNotNull { it.asObj() }?.forEach { sub -> bubbleSubButton(sub)?.let { row.addView(it, wrap(left = 6)) } }
        return row
    }

    /** Bubble's small sub-buttons: icon plus optional name / state, in a little pill. */
    private fun bubbleSubButton(cfg: JsonObject): View? {
        val chip = horizontal().apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(4), dp(10), dp(4))
            if (cfg.bool("show_background", true)) {
                background = GradientDrawable().apply { cornerRadius = dp(12).toFloat(); setColor(0x1AFFFFFF) }
            }
        }
        val icon = MdiIcons.view(context, 16f, HaFormat.COLOR_INACTIVE)
        val label = text(12f, Color.WHITE)
        if (cfg.bool("show_icon", true)) chip.addView(icon, LinearLayout.LayoutParams(dp(20), dp(20)))
        chip.addView(label, wrap(left = 4))
        val id = cfg.str("entity")
        if (id == null) {
            MdiIcons.set(icon, cfg.str("icon")?.let { HaFormat.stripIconPrefix(it) } ?: "circle-small")
            label.text = cfg.str("name").orEmpty()
            label.visibility = if (label.text.isEmpty()) View.GONE else View.VISIBLE
            return chip
        }
        bindEntity(id) { e ->
            if (e == null) return@bindEntity
            MdiIcons.set(icon, HaFormat.iconName(e, cfg.str("icon")))
            icon.setTextColor(accent(cfg, e))
            val text = listOfNotNull(
                if (cfg.bool("show_name", false)) displayName(cfg, e, id) else null,
                if (cfg.bool("show_state", false)) HaFormat.stateText(e) else null
            ).joinToString(" · ")
            label.text = text
            label.visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
            label.setTextColor(if (HaFormat.isAlert(e)) HaFormat.RED else Color.WHITE)
        }
        return chip
    }

    /** horizontal-buttons-stack: numbered keys 1_name / 1_icon / 1_entity ... as a row of pills. */
    private fun bubbleButtonsStack(cfg: JsonObject): View {
        val flow = FlowLayout(context, dp(8), dp(8))
        var i = 1
        while (cfg.has("${i}_name") || cfg.has("${i}_link") || cfg.has("${i}_icon")) {
            val sub = JsonObject().apply {
                cfg.str("${i}_name")?.let { addProperty("name", it) }
                cfg.str("${i}_icon")?.let { addProperty("icon", it) }
                cfg.str("${i}_entity")?.let { addProperty("entity", it) }
                addProperty("show_name", true)
            }
            bubbleSubButton(sub)?.let { flow.addView(it) }
            i++
        }
        return flow
    }

    // ---- badges, conditions ------------------------------------------------------------------

    private fun badgeRow(badges: JsonArray): View {
        val flow = FlowLayout(context, dp(8), dp(8))
        entityList(badges).forEach { (id, item) -> flow.addView(chip(id, item, compact = false)) }
        return flow
    }

    private fun chip(id: String, item: JsonObject, compact: Boolean): View {
        val chip = horizontal().apply {
            gravity = Gravity.CENTER_VERTICAL
            background = glass()
            setPadding(dp(10), dp(5), dp(12), dp(5))
        }
        val icon = MdiIcons.view(context, 18f, HaFormat.COLOR_INACTIVE)
        chip.addView(icon, LinearLayout.LayoutParams(dp(22), dp(22)))
        val texts = vertical()
        val name = text(11f, LABEL)
        val state = text(13f, Color.WHITE)
        val showName = !compact && item.bool("show_name", false)
        if (showName) texts.addView(name, wrap())
        texts.addView(state, wrap())
        chip.addView(texts, wrap(left = 6))
        bindEntity(id) { e ->
            name.text = displayName(item, e, id)
            if (e == null) { state.text = "—"; return@bindEntity }
            MdiIcons.set(icon, HaFormat.iconName(e, item.str("icon")))
            icon.setTextColor(accent(item, e))
            state.text = HaFormat.stateText(e)
            state.setTextColor(if (HaFormat.isAlert(e)) HaFormat.RED else Color.WHITE)
        }
        return chip
    }

    private fun bindVisibility(view: View, conditions: JsonArray) {
        val ids = LinkedHashSet<String>()
        conditions.forEach { collectEntities(it, ids) }
        val update = { view.visibility = if (conditionsMet(conditions)) View.VISIBLE else View.GONE }
        if (ids.isEmpty()) update() else bind(ids, update)
    }

    fun conditionsMet(conditions: JsonArray): Boolean = conditions.all { c -> c.asObj()?.let { conditionMet(it) } ?: true }

    private fun conditionMet(c: JsonObject): Boolean {
        return when (c.str("condition")) {
            null, "state" -> {
                val state = entities[c.str("entity") ?: return true]?.state ?: "unavailable"
                val wanted = c.get("state")
                val not = c.get("state_not")
                (wanted == null || matchesState(state, wanted)) && (not == null || !matchesState(state, not))
            }
            "numeric_state" -> {
                val v = entities[c.str("entity") ?: return true]?.state?.toDoubleOrNull() ?: return false
                (c.double("above")?.let { v > it } ?: true) && (c.double("below")?.let { v < it } ?: true)
            }
            "and" -> c.arr("conditions")?.let { conditionsMet(it) } ?: true
            "or" -> c.arr("conditions")?.any { it.asObj()?.let { o -> conditionMet(o) } ?: false } ?: true
            "not" -> c.arr("conditions")?.none { it.asObj()?.let { o -> conditionMet(o) } ?: false } ?: true
            else -> true // screen, user, time, location: always shown on the frame
        }
    }

    private fun matchesState(state: String, wanted: JsonElement): Boolean = when {
        wanted.isJsonArray -> wanted.asJsonArray.any { it.isJsonPrimitive && it.asString == state }
        wanted.isJsonPrimitive -> wanted.asString == state
        else -> false
    }

    // ---- helpers -----------------------------------------------------------------------------

    private fun displayName(cfg: JsonObject, e: HaEntity?, id: String): String =
        cfg.get("name")?.takeIf { it.isJsonPrimitive }?.asString ?: e?.friendlyName ?: id

    private fun accent(cfg: JsonObject, e: HaEntity): Int =
        if (HaFormat.isActive(e)) HaFormat.namedColor(cfg.str("color"))?.takeIf { cfg.str("color") != "state" } ?: HaFormat.stateColor(e)
        else HaFormat.iconColor(e)

    private fun withAlpha(color: Int, alpha: Int) = (color and 0x00FFFFFF) or (alpha shl 24)

    private fun accentOrPrimary(cfg: JsonObject, e: HaEntity): Int =
        HaFormat.namedColor(cfg.str("color")) ?: if (HaFormat.isActive(e)) HaFormat.stateColor(e) else PRIMARY

    /** Tile "state_content": "state", "last_changed", or attribute names, joined with " · ". */
    private fun stateContent(cfg: JsonObject, e: HaEntity): String {
        val content = cfg.get("state_content") ?: return HaFormat.stateText(e)
        val parts = if (content.isJsonArray) content.asJsonArray.map { it.asString } else listOf(content.asString)
        return parts.mapNotNull { part ->
            when (part) {
                "state" -> HaFormat.stateText(e)
                "last_changed", "last-changed" -> HaFormat.relativeTime(Instant.ofEpochMilli((e.lastChanged * 1000).toLong()), Instant.now())
                "last_updated", "last-updated" -> null
                else -> e.attr(part)?.let { formatValue(it) }
            }
        }.joinToString(" · ").ifEmpty { HaFormat.stateText(e) }
    }

    private fun valueAndUnit(e: HaEntity): Pair<String, String> {
        val numeric = e.state.toDoubleOrNull()
        return if (numeric != null && !e.isUnavailable && e.domain in setOf("sensor", "number", "input_number")) {
            HaFormat.number(numeric) to e.unit.orEmpty()
        } else {
            HaFormat.stateText(e) to ""
        }
    }

    private fun formatValue(v: JsonElement): String = when {
        v.isJsonPrimitive && v.asJsonPrimitive.isNumber -> HaFormat.number(v.asDouble)
        v.isJsonPrimitive -> HaFormat.humanize(v.asString)
        else -> v.toString()
    }

    private fun entityList(arr: JsonArray?): List<Pair<String, JsonObject>> = arr?.mapNotNull { item ->
        when {
            item.isJsonPrimitive -> item.asString to JsonObject()
            item.isJsonObject -> item.asJsonObject.str("entity")?.let { it to item.asJsonObject }
            else -> null
        }
    }.orEmpty()

    private fun cacheBust(path: String) = path + (if (path.contains('?')) "&" else "?") + "_t=" + System.currentTimeMillis() / 10_000

    private fun markdown(source: String): CharSequence {
        val html = source.lines().joinToString("<br>") { raw ->
            var line = TextUtils.htmlEncode(raw).replace("&lt;br&gt;", "")
            line = when {
                line.startsWith("### ") -> "<b>${line.substring(4)}</b>"
                line.startsWith("## ") -> "<big><b>${line.substring(3)}</b></big>"
                line.startsWith("# ") -> "<big><big><b>${line.substring(2)}</b></big></big>"
                line.startsWith("- ") || line.startsWith("* ") -> "&#8226; " + line.substring(2)
                else -> line
            }
            line.replace(Regex("\\*\\*(.+?)\\*\\*"), "<b>$1</b>")
                .replace(Regex("(?<![*\\w])[*_](.+?)[*_](?![*\\w])"), "<i>$1</i>")
                .replace(Regex("\\[(.+?)]\\((.+?)\\)"), "$1")
                // <ha-icon icon="mdi:x"></ha-icon> has no meaning here
                .replace(Regex("&lt;ha-icon[^&]*&gt;(&lt;/ha-icon&gt;)?"), "")
        }
        @Suppress("DEPRECATION")
        return Html.fromHtml(html)
    }

    private fun cardTitle(cfg: JsonObject): TextView? {
        val title = cfg.str("title")?.takeIf { it.isNotBlank() } ?: return null
        return text(13f, LABEL, bold = true, caps = true).apply { text = title }
    }

    private fun pictureView(cfg: JsonObject, widthPx: Int) = ImageView(context).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        setBackgroundColor(0x14FFFFFF)
        val ratio = parseAspectRatio(cfg.str("aspect_ratio")) ?: (9f / 16f)
        layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (widthPx * ratio).toInt())
    }

    /** "16x9", "16:9", "56%" → height/width. */
    private fun parseAspectRatio(value: String?): Float? {
        if (value.isNullOrBlank()) return null
        value.removeSuffix("%").toFloatOrNull()?.takeIf { value.endsWith("%") }?.let { return it / 100f }
        val parts = value.split('x', ':', '/')
        if (parts.size == 2) {
            val w = parts[0].trim().toFloatOrNull() ?: return null
            val h = parts[1].trim().toFloatOrNull() ?: return null
            if (w > 0) return h / w
        }
        return null
    }

    private fun roundedFrame(child: View): View = FrameLayout(context).apply {
        background = glass()
        addView(child)
    }

    private fun cardBox() = vertical().apply {
        background = glass()
        setPadding(dp(16), dp(14), dp(16), dp(14))
    }

    /**
     * Frosted Glass card (the HA theme of the same name): a near-transparent panel over the blurred
     * wallpaper, lighter at the top, with a hairline light edge. [tint] washes it with a state colour.
     */
    private fun glass(tint: Int? = null) = GradientDrawable(
        GradientDrawable.Orientation.TOP_BOTTOM,
        if (tint == null) intArrayOf(BubblePill.GLASS_TOP, BubblePill.GLASS_BOTTOM)
        else intArrayOf(withAlpha(tint, 0x3D), withAlpha(tint, 0x17))
    ).apply {
        cornerRadius = dp(18).toFloat()
        setStroke(hairline(), if (tint == null) BubblePill.EDGE else withAlpha(tint, 0x66))
    }

    private fun hairline() = maxOf(1, (density * 0.75f).roundToInt())

    /** Frosted Glass icon backgrounds are rounded squares (14px on a 40px icon), not circles. */
    private fun iconTile(sizeDp: Int) = GradientDrawable().apply { cornerRadius = dp(sizeDp) * 0.35f }

    private fun vertical() = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    // no baseline alignment: it shifts icon shapes up to line up with the text's baseline
    private fun horizontal() = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        isBaselineAligned = false
    }

    private fun text(sizeSp: Float, color: Int, bold: Boolean = false, light: Boolean = false, caps: Boolean = false) =
        TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
            setTextColor(color)
            setShadowLayer(4f, 0f, 1f, 0x40000000)
            // setSingleLine (not just maxLines = 1): KitKat only ellipsizes single-line text,
            // otherwise it word-wraps and clips ("Living" for "Living room")
            setSingleLine(true)
            ellipsize = TextUtils.TruncateAt.END
            includeFontPadding = false
            setLineSpacing(dp(2).toFloat(), 1f)
            when {
                bold -> typeface = Typeface.create("sans-serif", Typeface.BOLD)
                light -> typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
            }
            setAllCaps(caps)
        }

    private fun dp(v: Int) = (v * density).roundToInt()

    private fun wrap(left: Int = 0, top: Int = 0, right: Int = 0, bottom: Int = 0) =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            leftMargin = dp(left); topMargin = dp(top); rightMargin = dp(right); bottomMargin = dp(bottom)
        }

    private fun matchWrap(top: Int = 0, bottom: Int = 0) =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(top); bottomMargin = dp(bottom)
        }

    companion object {
        private const val SECONDARY = 0xB8EAEBEE.toInt()
        private const val LABEL = 0x99EAEBEE.toInt()
        private const val PRIMARY = 0xFF7AA2BE.toInt()
        private const val ICON_BG = 0x1CFFFFFF
        private val SERIES = intArrayOf(0xFF4BA3F5.toInt(), 0xFFFF9800.toInt(), 0xFF4CC38A.toInt(), 0xFFB27CF0.toInt(), 0xFFF0626B.toInt(), 0xFF2BC8E0.toInt())
        private val TOGGLES = setOf("light", "switch", "input_boolean", "fan")

        private fun JsonElement.asObj(): JsonObject? = if (isJsonObject) asJsonObject else null
        fun JsonObject.str(k: String): String? = get(k)?.takeIf { it.isJsonPrimitive }?.asString
        fun JsonObject.bool(k: String, default: Boolean): Boolean = get(k)?.takeIf { it.isJsonPrimitive }?.let { runCatching { it.asBoolean }.getOrNull() } ?: default
        fun JsonObject.int(k: String): Int? = get(k)?.takeIf { it.isJsonPrimitive }?.let { runCatching { it.asInt }.getOrNull() }
        fun JsonObject.double(k: String): Double? = get(k)?.takeIf { it.isJsonPrimitive }?.let { runCatching { it.asDouble }.getOrNull() }
        fun JsonObject.obj(k: String): JsonObject? = get(k)?.takeIf { it.isJsonObject }?.asJsonObject
        fun JsonObject.arr(k: String): JsonArray? = get(k)?.takeIf { it.isJsonArray }?.asJsonArray
    }
}
