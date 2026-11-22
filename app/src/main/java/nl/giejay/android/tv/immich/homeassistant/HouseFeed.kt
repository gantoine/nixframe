package nl.giejay.android.tv.immich.homeassistant

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import nl.giejay.android.tv.immich.BuildConfig
import nl.giejay.android.tv.immich.ambient.Takeover
import nl.giejay.android.tv.immich.ambient.TakeoverRules
import nl.giejay.android.tv.immich.shared.prefs.HA_DASHBOARD
import nl.giejay.android.tv.immich.shared.prefs.HA_PRINT_STATUS_ENTITY
import nl.giejay.android.tv.immich.shared.prefs.HA_QUIET_HOURS_ENTITY
import nl.giejay.android.tv.immich.shared.prefs.HA_TOKEN
import nl.giejay.android.tv.immich.shared.prefs.HA_URL
import nl.giejay.android.tv.immich.shared.prefs.PreferenceManager
import okhttp3.OkHttpClient
import timber.log.Timber
import java.time.Instant

/**
 * Keeps the ambient screens' view of the house live: reads the configured dashboard to learn which
 * entities matter ([DashboardScan]), streams their states, the weather entity's daily forecast and
 * Home Assistant's own notifications, and reports the moments worth taking over the screen for.
 *
 * Main thread only. One instance lives as long as the ambient screen is started.
 */
class HouseFeed(context: Context, private val listener: Listener) : HaConnection.Listener {

    interface Listener {
        /** Something on screen may have changed. Coalesced; called on the main thread. */
        fun onHouseChanged()

        /** A moment worth opening a screen for (already filtered for news, not for quiet hours). */
        fun onTakeover(takeover: Takeover)

        /** The doorbell or its motion sensor fired (keeps the doorbell view open). */
        fun onDoorActivity()
    }

    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private var connection: HaConnection? = null
    private var dashboard: String? = null
    private var viewKey: String? = null
    private var subscriptions = mutableListOf<Int>()
    private val subscribed = HashSet<String>()

    var client: OkHttpClient? = null
        private set
    var baseUrl: String = ""
        private set
    var token: String = ""
        private set

    var refs: List<DashboardRef> = emptyList()
        private set
    var forecast: JsonArray? = null
        private set
    val persistent = LinkedHashMap<String, PersistentNotice>()
    private val deviceOf = HashMap<String, String>()

    /** Null when all is well, else a short description of why the house data is missing. */
    var problem: String? = null
        private set

    val configured: Boolean get() = baseUrl.isNotEmpty() && token.isNotEmpty()

    private val quietEntity: String get() = PreferenceManager.get(HA_QUIET_HOURS_ENTITY).trim()
    private val printEntity: String get() = PreferenceManager.get(HA_PRINT_STATUS_ENTITY).trim()

    /** Last state seen of each entity whose changes can trigger a takeover. */
    private val lastStates = HashMap<String, String>()

    private val changed = Runnable { listener.onHouseChanged() }

    fun house(): House = House(refs, connection?.entities ?: emptyMap(), deviceOf)

    fun entity(id: String): HaEntity? = connection?.entities?.get(id)

    /** The quiet-hours entity's state, or null when none is configured. */
    fun quietState(): String? = quietEntity.takeIf { it.isNotEmpty() }?.let { entity(it)?.state ?: "unavailable" }
    val quietConfigured: Boolean get() = quietEntity.isNotEmpty()

    fun start() {
        if (connection != null) return
        baseUrl = PreferenceManager.get(HA_URL).trim().ifEmpty { BuildConfig.HA_URL }.trimEnd('/')
        token = PreferenceManager.get(HA_TOKEN).trim().ifEmpty { BuildConfig.HA_TOKEN }
        val (d, v) = DashboardScan.parseSetting(PreferenceManager.get(HA_DASHBOARD).ifBlank { BuildConfig.HA_DASHBOARD })
        dashboard = d
        viewKey = v
        if (!configured) {
            problem = "Home Assistant isn't set up"
            notifyChanged()
            return
        }
        val http = HaHttp.client(appContext)
        client = http
        connection = HaConnection(http, baseUrl, token, this).also { it.start() }
    }

    fun stop() {
        main.removeCallbacks(changed)
        connection?.stop()
        connection = null
        subscriptions.clear()
        subscribed.clear()
        lastStates.clear()
    }

    private fun notifyChanged() {
        main.removeCallbacks(changed)
        main.postDelayed(changed, 250)
    }

    // ---- HaConnection.Listener ------------------------------------------------------------

    override fun onConnected() {
        problem = null
        subscriptions.clear()
        subscribed.clear()
        loadDashboard()
        loadRegistry()
        val conn = connection ?: return
        conn.subscribe("subscribe_events", JsonObject().apply { addProperty("event_type", "lovelace_updated") }) { event ->
            val path = event.getAsJsonObject("data")?.get("url_path")?.takeIf { it.isJsonPrimitive }?.asString
            if (path == dashboard || (path == null && dashboard == null)) {
                Timber.i("Dashboard %s changed in Home Assistant, reloading", dashboard ?: "lovelace")
                loadDashboard()
            }
        }
        conn.subscribe("persistent_notification/subscribe") { event -> applyPersistent(event) }
        notifyChanged()
    }

    override fun onAuthFailed(message: String) {
        problem = "Home Assistant rejected the access token"
        notifyChanged()
    }

    override fun onDisconnected(error: String?) {
        problem = error ?: "Can't reach Home Assistant"
        notifyChanged()
    }

    override fun onEntitiesChanged(ids: Set<String>) {
        val house = house()
        val now = Instant.now()
        val doorbellId = house.doorbell?.id
        val motionId = house.motion?.id
        val safetyIds = (house.smokeAlarms + house.leakSensors).map { it.second.id }.toSet()
        val print = printEntity
        ids.forEach { id ->
            val e = entity(id) ?: return@forEach
            val old = lastStates[id]
            when (id) {
                doorbellId -> if (TakeoverRules.eventFired(old, e.state, now)) {
                    listener.onDoorActivity()
                    listener.onTakeover(Takeover.DOORBELL)
                }
                motionId -> if (TakeoverRules.eventFired(old, e.state, now)) listener.onDoorActivity()
                in safetyIds -> if (TakeoverRules.safetyTripped(old, e.state)) listener.onTakeover(Takeover.SAFETY)
                print -> if (TakeoverRules.printFinished(old, e.state)) listener.onTakeover(Takeover.PRINT_FINISHED)
            }
            lastStates[id] = e.state
        }
        // the doorbell's device siblings (camera, motion) can only be found once the doorbell's state is in
        subscribeMissing(house.doorbellSiblings())
        notifyChanged()
    }

    // ---- requests -------------------------------------------------------------------------

    private fun loadDashboard() {
        val conn = connection ?: return
        val fields = JsonObject().apply { dashboard?.let { addProperty("url_path", it) } }
        conn.send("lovelace/config", fields) { success, result, error ->
            if (!success || result == null || !result.isJsonObject) {
                val code = error?.get("code")?.takeIf { it.isJsonPrimitive }?.asString
                problem = if (code == "config_not_found") "Dashboard \"${dashboard ?: "lovelace"}\" wasn't found" else "Couldn't load the dashboard"
                notifyChanged()
                return@send
            }
            val view = DashboardScan.pickView(result.asJsonObject, viewKey)
            refs = view?.let { DashboardScan.scan(it) }.orEmpty()
            Timber.i("Ambient: %d entities on dashboard %s", refs.size, dashboard ?: "lovelace")
            subscribeMissing(refs.map { it.entityId } + listOfNotNull(quietEntity.ifEmpty { null }, printEntity.ifEmpty { null }))
            refs.firstOrNull { it.entityId.startsWith("weather.") }?.let { subscribeForecast(it.entityId) }
            notifyChanged()
        }
    }

    /** Entity ID -> device ID, to find the doorbell's camera and pair sensors of one device. */
    private fun loadRegistry() {
        val conn = connection ?: return
        conn.send("config/entity_registry/list_for_display") { success, result, _ ->
            if (!success || result == null || !result.isJsonObject) {
                Timber.w("Entity registry unavailable; the doorbell camera falls back to the dashboard's")
                return@send
            }
            deviceOf.clear()
            result.asJsonObject.get("entities")?.takeIf { it.isJsonArray }?.asJsonArray?.forEach { item ->
                val o = item.takeIf { it.isJsonObject }?.asJsonObject ?: return@forEach
                val id = o.get("ei")?.takeIf { it.isJsonPrimitive }?.asString ?: return@forEach
                val device = o.get("di")?.takeIf { it.isJsonPrimitive }?.asString ?: return@forEach
                deviceOf[id] = device
            }
            subscribeMissing(house().doorbellSiblings())
            notifyChanged()
        }
    }

    private fun subscribeMissing(ids: Collection<String>) {
        val conn = connection ?: return
        val missing = ids.filter { it.isNotEmpty() && subscribed.add(it) }
        if (missing.isEmpty()) return
        conn.subscribeEntities(missing)?.let { subscriptions += it }
    }

    private var forecastFor: String? = null

    private fun subscribeForecast(entityId: String) {
        val conn = connection ?: return
        if (forecastFor == entityId && subscribed.contains("forecast:$entityId")) return
        forecastFor = entityId
        subscribed += "forecast:$entityId"
        val fields = JsonObject().apply {
            addProperty("entity_id", entityId)
            addProperty("forecast_type", "daily")
        }
        conn.subscribe("weather/subscribe_forecast", fields) { event ->
            forecast = event.getAsJsonArray("forecast")
            notifyChanged()
        }?.let { subscriptions += it }
    }

    private fun applyPersistent(event: JsonObject) {
        val type = event.get("type")?.takeIf { it.isJsonPrimitive }?.asString
        val items = event.getAsJsonObject("notifications") ?: JsonObject()
        if (type == "current") persistent.clear()
        items.entrySet().forEach { (id, value) ->
            if (type == "removed") {
                persistent.remove(id)
            } else if (value.isJsonObject) {
                val o = value.asJsonObject
                persistent[id] = PersistentNotice(
                    id,
                    o.get("title")?.takeIf { it.isJsonPrimitive }?.asString,
                    o.get("message")?.takeIf { it.isJsonPrimitive }?.asString
                )
            }
        }
        notifyChanged()
    }

    /** Absolute URL for a Home Assistant path (camera snapshots). */
    fun resolve(path: String): String = if (path.startsWith("http")) path else baseUrl + path
}
