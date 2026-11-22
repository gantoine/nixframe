package nl.giejay.android.tv.immich.homeassistant

import com.google.gson.JsonArray
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** A quiet notification line: an MDI icon name and an uppercase-able label. */
data class Notice(val key: String, val icon: String, val label: String, val urgent: Boolean = false)

/** A notification Home Assistant raised itself (persistent_notification). */
data class PersistentNotice(val id: String, val title: String?, val message: String?)

data class Presence(val label: String, val names: String, val allHome: Boolean)

data class SafetyLine(val label: String, val ok: Boolean)

data class RoomRow(val entityId: String, val name: String, val detail: String, val temperature: String)

data class ForecastDay(val day: String, val high: String, val low: String?)

data class Outside(val icon: String, val temperature: String, val high: String?, val low: String?, val days: List<ForecastDay>)

/**
 * What the ambient screens show, worked out from the dashboard's entities ([DashboardScan]) and
 * their live states. Pure: no Android types, unit tested.
 *
 * Which entity plays which part is decided by domain and device class, never by entity ID:
 * weather = the first `weather.*`, people = `person.*`, smoke/CO = binary sensors of class
 * smoke/carbon_monoxide/gas, leaks = class moisture, the doorbell = an `event.*` of class
 * doorbell, motion = an `event.*` of class motion, rooms = the thermostat plus temperature sensors.
 */
class House(
    val refs: List<DashboardRef>,
    private val entities: Map<String, HaEntity>,
    /** entity id -> device id, from the entity registry (may be empty). */
    private val deviceOf: Map<String, String> = emptyMap()
) {
    private fun live(): List<Pair<DashboardRef, HaEntity>> = refs.mapNotNull { r -> entities[r.entityId]?.let { r to it } }

    private fun refsOf(domain: String, classes: Set<String>? = null): List<Pair<DashboardRef, HaEntity>> =
        live().filter { (_, e) -> e.domain == domain && (classes == null || e.deviceClass in classes) }

    fun name(ref: DashboardRef, e: HaEntity): String = ref.name ?: e.friendlyName

    val weather: HaEntity? get() = refsOf("weather").firstOrNull()?.second
    val people: List<HaEntity> get() = refsOf("person").map { it.second }
    val smokeAlarms: List<Pair<DashboardRef, HaEntity>> get() = refsOf("binary_sensor", SMOKE_CLASSES)
    val leakSensors: List<Pair<DashboardRef, HaEntity>> get() = refsOf("binary_sensor", setOf("moisture"))
    val doorbell: HaEntity? get() = refsOf("event", setOf("doorbell")).firstOrNull()?.second

    /** Motion at the door: a motion event on the dashboard, else one on the doorbell's device. */
    val motion: HaEntity?
        get() = refsOf("event", setOf("motion")).firstOrNull()?.second
            ?: doorbellDevice()?.let { device ->
                entities.values.firstOrNull { it.domain == "event" && it.deviceClass == "motion" && deviceOf[it.id] == device }
            }
    val thermostat: Pair<DashboardRef, HaEntity>? get() = refsOf("climate").firstOrNull()

    private fun doorbellDevice(): String? = doorbell?.let { deviceOf[it.id] }

    /**
     * Entities on the doorbell's device (from the entity registry) that the dashboard may not
     * list: its camera and motion events. The feed subscribes to these too.
     */
    fun doorbellSiblings(): List<String> {
        val device = doorbellDevice() ?: return emptyList()
        return deviceOf.filter { (id, d) -> d == device && (id.startsWith("camera.") || id.startsWith("event.")) }.keys.sorted()
    }

    /** The doorbell's camera: one on the doorbell's device, else the first camera on the dashboard. */
    fun doorbellCamera(): HaEntity? {
        val device = doorbellDevice()
        if (device != null) {
            entities.values.firstOrNull { it.domain == "camera" && deviceOf[it.id] == device }?.let { return it }
        }
        return refsOf("camera").firstOrNull()?.second
    }

    // ---- screen 1 -------------------------------------------------------------------------

    fun presence(): Presence? = presenceOf(people)

    /** Indoor temperature: the first room sensor on the dashboard, else the thermostat's reading. */
    fun indoorTemperature(): String? {
        temperatureSensors().firstOrNull()?.second?.state?.toDoubleOrNull()?.let { return AmbientFormat.temperature(it, 1) }
        return thermostat?.second?.attrDouble("current_temperature")?.let { AmbientFormat.temperature(it, 1) }
    }

    fun outsideNow(): Pair<String, String>? {
        val w = weather?.takeUnless { it.isUnavailable } ?: return null
        val t = w.attrDouble("temperature") ?: return null
        return HaFormat.weatherIcon(w.state) to outdoorTemperature(t, w)
    }

    /**
     * Outdoor temperatures are always shown in Celsius, whatever unit Home Assistant uses; indoor
     * ones are left in Home Assistant's unit. The forecast comes in the weather entity's unit too.
     */
    private fun outdoorTemperature(value: Double, weather: HaEntity): String {
        val fahrenheit = weather.attrString("temperature_unit")?.trim()?.endsWith("F") == true
        return AmbientFormat.temperature(if (fahrenheit) (value - 32.0) * 5.0 / 9.0 else value, 0)
    }

    /** Entities that need attention, then Home Assistant's own notifications; safety first. */
    fun notices(persistent: Collection<PersistentNotice>): List<Notice> {
        val fromEntities = live()
            .filter { (_, e) -> e.domain != "weather" && e.domain != "person" && HaFormat.isAlert(e) }
            .map { (ref, e) -> Notice(e.id, HaFormat.iconName(e), noticeLabel(name(ref, e), e), urgent = e.deviceClass in URGENT_CLASSES) }
        val fromHa = persistent.mapNotNull { p ->
            val label = p.title?.trim()?.takeIf { it.isNotEmpty() }
                ?: p.message?.lineSequence()?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }
                ?: return@mapNotNull null
            Notice("persistent:${p.id}", GENERIC_ALERT_ICON, label)
        }
        return fromEntities.sortedByDescending { it.urgent } + fromHa
    }

    // ---- glance ---------------------------------------------------------------------------

    fun statusLines(): List<SafetyLine> {
        val lines = mutableListOf<SafetyLine>()
        presence()?.let { lines += SafetyLine(it.label, true) }
        val smoke = smokeAlarms
        if (smoke.isNotEmpty()) {
            val tripped = smoke.filter { it.second.state == "on" }
            val classes = smoke.mapNotNull { it.second.deviceClass }.toSet()
            lines += if (tripped.isEmpty()) {
                SafetyLine(if ("smoke" in classes && classes.size > 1) "Smoke & CO clear" else if ("smoke" in classes) "No smoke" else "CO clear", true)
            } else {
                val e = tripped.first().second
                SafetyLine(if (e.deviceClass == "smoke") "Smoke detected" else "CO detected", false)
            }
        }
        val leaks = leakSensors
        if (leaks.isNotEmpty()) {
            val wet = leaks.filter { it.second.state == "on" }
            lines += if (wet.isEmpty()) SafetyLine("No leaks", true)
            else SafetyLine("Leak: " + wet.joinToString(", ") { AmbientFormat.stripSuffix(name(it.first, it.second), "leak sensor") }, false)
        }
        return lines
    }

    fun outside(forecast: JsonArray?, today: LocalDate, zone: ZoneId): Outside? {
        val w = weather?.takeUnless { it.isUnavailable } ?: return null
        val temp = w.attrDouble("temperature")?.let { outdoorTemperature(it, w) } ?: return null
        val days = forecastDays(forecast, zone, w)
        val todays = days.firstOrNull { it.first == today }?.second
        val upcoming = days.filter { it.first.isAfter(today) }.take(4).map { (date, f) ->
            ForecastDay(DAY_NAMES[date.dayOfWeek.value - 1], f.high, f.low)
        }
        return Outside(HaFormat.weatherIcon(w.state), temp, todays?.high, todays?.low, upcoming)
    }

    private class DayTemps(val high: String, val low: String?)

    /** Daily forecast entries by local date. Entries that don't parse are skipped. */
    private fun forecastDays(forecast: JsonArray?, zone: ZoneId, weather: HaEntity): List<Pair<LocalDate, DayTemps>> =
        forecast?.mapNotNull { item ->
            val o = item.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            val at = o.get("datetime")?.takeIf { it.isJsonPrimitive }?.asString?.let { HaFormat.parseTimestamp(it) } ?: return@mapNotNull null
            val high = o.get("temperature")?.takeIf { it.isJsonPrimitive }?.asDouble ?: return@mapNotNull null
            val low = o.get("templow")?.takeIf { it.isJsonPrimitive }?.asDouble
            at.atZone(zone).toLocalDate() to DayTemps(outdoorTemperature(high, weather), low?.let { outdoorTemperature(it, weather) })
        }?.distinctBy { it.first }.orEmpty()

    /** "Doorbell" and "Motion" with how long ago each last fired. */
    fun frontDoor(now: Instant): List<Pair<String, String>> = listOfNotNull(
        doorbell?.let { "Doorbell" to (lastFired(it)?.let { t -> AmbientFormat.ago(t, now) } ?: "—") },
        motion?.let { "Motion" to (lastFired(it)?.let { t -> AmbientFormat.ago(t, now) } ?: "—") }
    )

    /** Thermostat first, then the temperature sensors in dashboard order, at most [max] rows. */
    fun rooms(max: Int = 5): List<RoomRow> {
        val rows = mutableListOf<RoomRow>()
        thermostat?.let { (ref, e) ->
            val current = e.attrDouble("current_temperature")
            rows += RoomRow(e.id, ref.name ?: "Thermostat", AmbientFormat.thermostatDetail(e), current?.let { AmbientFormat.temperature(it, 1) } ?: "—")
        }
        temperatureSensors().forEach { (ref, e) ->
            val humidity = humidityFor(ref, e)?.state?.toDoubleOrNull()
            rows += RoomRow(
                e.id,
                roomName(ref, e),
                humidity?.let { "Humidity ${AmbientFormat.number(it, 0)}%" } ?: "",
                e.state.toDoubleOrNull()?.let { AmbientFormat.temperature(it, 1) } ?: "—"
            )
        }
        return rows.take(max)
    }

    private fun temperatureSensors(): List<Pair<DashboardRef, HaEntity>> = live().filter { (_, e) ->
        e.domain == "sensor" && (e.deviceClass == "temperature" || e.unit == "°F" || e.unit == "°C")
    }

    /** The humidity sensor that goes with a temperature sensor: same card, else same device. */
    private fun humidityFor(ref: DashboardRef, temperature: HaEntity): HaEntity? {
        val humidities = live().filter { (_, e) -> e.domain == "sensor" && e.deviceClass == "humidity" }
        humidities.firstOrNull { it.first.card == ref.card }?.let { return it.second }
        val device = deviceOf[temperature.id] ?: return null
        return humidities.firstOrNull { deviceOf[it.second.id] == device }?.second
    }

    private fun roomName(ref: DashboardRef, e: HaEntity): String =
        ref.name ?: AmbientFormat.stripSuffix(AmbientFormat.stripSuffix(e.friendlyName, "temperature"), "climate")

    companion object {
        const val GENERIC_ALERT_ICON = "alert-circle-outline"
        /** Fixed English abbreviations: no dependence on the desugared java.time text providers. */
        private val DAY_NAMES = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
        val SMOKE_CLASSES = setOf("smoke", "carbon_monoxide", "gas")
        private val URGENT_CLASSES = SMOKE_CLASSES + "moisture"

        /** When an event entity last fired (its state is that timestamp), else its last change. */
        fun lastFired(e: HaEntity): Instant? {
            if (e.domain == "event") return HaFormat.parseTimestamp(e.state)
            if (e.lastChanged <= 0) return null
            return Instant.ofEpochMilli((e.lastChanged * 1000).toLong())
        }

        fun presenceOf(people: List<HaEntity>): Presence? {
            val known = people.filterNot { it.isUnavailable }
            if (known.isEmpty()) return null
            val home = known.filter { it.state == "home" }
            val names = home.joinToString(", ") { it.friendlyName }
            val label = when {
                home.size == known.size && known.size == 1 -> "${firstName(home[0])} home"
                home.size == known.size && known.size == 2 -> "Both home"
                home.size == known.size -> "Everyone home"
                home.isEmpty() -> "Nobody home"
                else -> home.joinToString(" & ") { firstName(it) } + " home"
            }
            return Presence(label, names, home.size == known.size)
        }

        private fun firstName(e: HaEntity) = e.friendlyName.trim().substringBefore(' ')

        /** "Basement Leak Sensor · Wet"; a problem sensor's name says it all on its own. */
        fun noticeLabel(name: String, e: HaEntity): String {
            val state = HaFormat.stateText(e)
            if (e.domain == "binary_sensor" && e.deviceClass in setOf("problem", null)) return name
            if (name.contains(state, ignoreCase = true)) return name
            return "$name · $state"
        }
    }
}
