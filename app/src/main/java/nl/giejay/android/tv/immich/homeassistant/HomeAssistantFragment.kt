package nl.giejay.android.tv.immich.homeassistant

import android.graphics.drawable.GradientDrawable
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.leanback.app.BrowseSupportFragment
import androidx.lifecycle.lifecycleScope
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import nl.giejay.android.tv.immich.BuildConfig
import nl.giejay.android.tv.immich.R
import nl.giejay.android.tv.immich.homeassistant.LovelaceRenderer.Companion.arr
import nl.giejay.android.tv.immich.homeassistant.LovelaceRenderer.Companion.str
import nl.giejay.android.tv.immich.shared.prefs.HA_DASHBOARD
import nl.giejay.android.tv.immich.shared.prefs.HA_TOKEN
import nl.giejay.android.tv.immich.shared.prefs.HA_URL
import nl.giejay.android.tv.immich.shared.prefs.PreferenceManager
import nl.giejay.mediaslider.view.SafeTextClock
import timber.log.Timber
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Sidebar page showing one Home Assistant dashboard, read-only. The dashboard is designed in
 * Home Assistant; this fetches its config over the websocket API, draws it natively
 * ([LovelaceRenderer]) and keeps it live. Saving the dashboard in HA re-renders it here.
 */
class HomeAssistantFragment : Fragment(), BrowseSupportFragment.MainFragmentAdapterProvider, HaConnection.Listener {

    private val mainFragmentAdapter = BrowseSupportFragment.MainFragmentAdapter(this)
    override fun getMainFragmentAdapter() = mainFragmentAdapter

    private lateinit var status: TextView
    private lateinit var badge: TextView
    private lateinit var scroll: ScrollView
    private lateinit var content: LinearLayout

    private var connection: HaConnection? = null
    private var renderer: LovelaceRenderer? = null
    private var images: HaImageLoader? = null
    private var dashboard: String? = null
    private var viewKey: String? = null
    private var dashboardSubscriptions = mutableListOf<Int>()

    private val handler = Handler(Looper.getMainLooper())
    private val cameraTick = object : Runnable {
        override fun run() {
            renderer?.periodic?.forEach { it() }
            handler.postDelayed(this, CAMERA_REFRESH_MS)
        }
    }
    private val historyTick = object : Runnable {
        override fun run() {
            loadHistories()
            handler.postDelayed(this, HISTORY_REFRESH_MS)
        }
    }
    private val minuteTick = object : Runnable {
        override fun run() {
            renderer?.refreshAll()
            handler.postDelayed(this, 60_000L)
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val context = requireContext()
        val density = resources.displayMetrics.density
        val root = FrameLayout(context).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(0xFF1E2A3B.toInt(), 0xFF141C29.toInt(), 0xFF0B1019.toInt()))
            isFocusable = true
            isFocusableInTouchMode = true
            descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        }
        scroll = ScrollView(context).apply {
            isFocusable = true
            isFocusableInTouchMode = true
            isVerticalScrollBarEnabled = false
            visibility = View.GONE
        }
        content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            // top clears the sidebar page title, which the browse fragment draws top-right
            val h = (32 * density).toInt()
            setPadding(h, (28 * density).toInt(), h, (36 * density).toInt())
        }
        scroll.addView(content, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(scroll, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        status = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            setTextColor(0xE6FFFFFF.toInt())
            gravity = Gravity.CENTER
            maxWidth = (620 * density).toInt()
        }
        root.addView(status, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        badge = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(0xE6FFFFFF.toInt())
            background = GradientDrawable().apply { cornerRadius = 14 * density; setColor(0xCC3A2A12.toInt()) }
            setPadding((12 * density).toInt(), (5 * density).toInt(), (12 * density).toInt(), (5 * density).toInt())
            visibility = View.GONE
        }
        root.addView(badge, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END).apply {
            topMargin = (14 * density).toInt(); rightMargin = (20 * density).toInt()
        })
        return root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        mainFragmentAdapter.fragmentHost?.notifyDataReady(mainFragmentAdapter)
        // warm the icon font off the main thread
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) { MdiIcons.load(requireContext().applicationContext) }
    }

    override fun onStart() {
        super.onStart()
        val url = PreferenceManager.get(HA_URL).trim().ifEmpty { BuildConfig.HA_URL }.trimEnd('/')
        val token = PreferenceManager.get(HA_TOKEN).trim().ifEmpty { BuildConfig.HA_TOKEN }
        val setting = PreferenceManager.get(HA_DASHBOARD).trim().ifEmpty { BuildConfig.HA_DASHBOARD }.trim('/')
        if (url.isEmpty() || token.isEmpty()) {
            showStatus(getString(R.string.ha_not_configured))
            return
        }
        // "dashboard-id" or "dashboard-id/view-path"
        dashboard = setting.substringBefore('/').ifEmpty { null }?.takeUnless { it == "lovelace" }
        viewKey = setting.substringAfter('/', "").ifEmpty { null }
        val client = HaHttp.client(requireContext())
        images = HaImageLoader(client, url, token, viewLifecycleOwner.lifecycleScope)
        if (renderer == null) showStatus(getString(R.string.ha_connecting))
        connection = HaConnection(client, url, token, this).also { it.start() }
        handler.postDelayed(minuteTick, 60_000L)
    }

    override fun onStop() {
        super.onStop()
        handler.removeCallbacksAndMessages(null)
        connection?.stop()
        connection = null
        dashboardSubscriptions.clear()
    }

    // ---- HaConnection.Listener ---------------------------------------------------------------

    override fun onConnected() {
        badge.visibility = View.GONE
        if (renderer == null) showStatus(getString(R.string.ha_loading))
        loadDashboard()
        // re-render when the dashboard is saved in Home Assistant
        connection?.subscribe("subscribe_events", JsonObject().apply { addProperty("event_type", "lovelace_updated") }) { event ->
            val path = event.getAsJsonObject("data")?.str("url_path")
            if (path == dashboard || (path == null && dashboard == null)) {
                Timber.i("Dashboard %s changed in Home Assistant, reloading", dashboard ?: "lovelace")
                loadDashboard()
            }
        }
    }

    override fun onAuthFailed(message: String) {
        showStatus(getString(R.string.ha_auth_failed))
    }

    override fun onDisconnected(error: String?) {
        if (renderer != null) {
            badge.text = getString(R.string.ha_reconnecting)
            badge.visibility = View.VISIBLE
        } else {
            showStatus(error ?: getString(R.string.ha_connecting))
        }
    }

    override fun onEntitiesChanged(ids: Set<String>) {
        renderer?.onEntitiesChanged(ids)
    }

    // ---- dashboard ---------------------------------------------------------------------------

    private fun loadDashboard() {
        val conn = connection ?: return
        val fields = JsonObject().apply { dashboard?.let { addProperty("url_path", it) } }
        conn.send("lovelace/config", fields) { success, result, error ->
            if (!isAdded) return@send
            if (!success || result == null || !result.isJsonObject) {
                val code = error?.str("code")
                showStatus(if (code == "config_not_found") getString(R.string.ha_dashboard_not_found, dashboard ?: "lovelace")
                           else error?.str("message") ?: getString(R.string.ha_error))
                return@send
            }
            render(result.asJsonObject)
        }
    }

    private fun render(config: JsonObject) {
        val conn = connection ?: return
        val loader = images ?: return
        if (config.has("strategy")) {
            showStatus(getString(R.string.ha_dashboard_strategy))
            return
        }
        val views = config.arr("views")?.mapNotNull { it.takeIf { v -> v.isJsonObject }?.asJsonObject }.orEmpty()
        val view = viewKey?.let { key -> views.firstOrNull { it.str("path") == key } ?: key.toIntOrNull()?.let { views.getOrNull(it) } }
            ?: views.firstOrNull()
        if (view == null) {
            showStatus(getString(R.string.ha_dashboard_empty))
            return
        }
        // drop the previous render's subscriptions (a reload after the dashboard was edited)
        dashboardSubscriptions.forEach { conn.unsubscribe(it) }
        dashboardSubscriptions.clear()
        handler.removeCallbacks(cameraTick)
        handler.removeCallbacks(historyTick)

        val r = LovelaceRenderer(requireContext(), conn.entities, loader)
        val width = (requireView().width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels) - content.paddingLeft - content.paddingRight
        val built = r.renderView(view, width)
        content.removeAllViews()
        content.addView(header())
        content.addView(built)
        renderer = r
        status.visibility = View.GONE
        scroll.visibility = View.VISIBLE

        conn.subscribeEntities(r.entityIds)?.let { dashboardSubscriptions += it }
        r.forecasts.forEach { f ->
            val fields = JsonObject().apply {
                addProperty("entity_id", f.entityId)
                addProperty("forecast_type", f.type)
            }
            conn.subscribe("weather/subscribe_forecast", fields) { event ->
                event.getAsJsonArray("forecast")?.let { f.onForecast(it) }
            }?.let { dashboardSubscriptions += it }
        }
        r.templates.forEach { t ->
            val fields = JsonObject().apply {
                addProperty("template", t.template)
                t.variables?.let { add("variables", it) }
            }
            conn.subscribe("render_template", fields) { event ->
                event.get("result")?.let { t.onResult(if (it.isJsonPrimitive) it.asString else it.toString()) }
            }?.let { dashboardSubscriptions += it }
        }
        if (r.periodic.isNotEmpty()) handler.postDelayed(cameraTick, CAMERA_REFRESH_MS)
        if (r.histories.isNotEmpty()) handler.post(historyTick)
    }

    /** Big clock and the date: the frame is mostly glanced at from across the room. */
    private fun header(): View {
        val context = requireContext()
        val density = resources.displayMetrics.density
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
            setPadding(0, 0, 0, (16 * density).toInt())
        }
        val time = SafeTextClock(context).apply {
            format12Hour = "h:mm"
            format24Hour = "H:mm"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 52f)
            setTextColor(0xFFFFFFFF.toInt())
            typeface = Typeface.create("sans-serif-thin", Typeface.NORMAL)
            includeFontPadding = false
        }
        row.addView(time)
        val date = SafeTextClock(context).apply {
            format12Hour = "EEEE, MMMM d"
            format24Hour = "EEEE, MMMM d"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setTextColor(0xB3FFFFFF.toInt())
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
            includeFontPadding = false
        }
        row.addView(date, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            leftMargin = (16 * density).toInt()
            bottomMargin = (8 * density).toInt()
        })
        return row
    }

    private fun loadHistories() {
        val conn = connection ?: return
        val r = renderer ?: return
        r.histories.forEach { h ->
            val end = Instant.now()
            val start = end.minus(h.hours.toLong(), ChronoUnit.HOURS)
            val fields = JsonObject().apply {
                addProperty("start_time", start.toString())
                addProperty("end_time", end.toString())
                add("entity_ids", JsonArray().apply { h.entityIds.forEach { add(it) } })
                addProperty("minimal_response", true)
                addProperty("no_attributes", true)
                addProperty("significant_changes_only", false)
            }
            conn.send("history/history_during_period", fields) { success, result, _ ->
                if (!success || result == null || !result.isJsonObject) return@send
                val series = result.asJsonObject.entrySet().associate { (id, states) ->
                    id to states.asJsonArray.mapNotNull { s ->
                        val o = s.asJsonObject
                        val v = o.get("s")?.asString?.toDoubleOrNull() ?: return@mapNotNull null
                        val t = (o.get("lu") ?: o.get("lc"))?.asDouble ?: return@mapNotNull null
                        t to v
                    }
                }
                h.onHistory(series, start.epochSecond.toDouble(), end.epochSecond.toDouble())
            }
        }
    }

    private fun showStatus(text: String) {
        if (!isAdded) return
        status.text = text
        status.visibility = View.VISIBLE
        scroll.visibility = View.GONE
        renderer = null
    }

    private companion object {
        const val CAMERA_REFRESH_MS = 10_000L
        const val HISTORY_REFRESH_MS = 5 * 60_000L
    }
}
