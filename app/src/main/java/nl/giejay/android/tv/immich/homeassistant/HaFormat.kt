package nl.giejay.android.tv.immich.homeassistant

import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * How Home Assistant's frontend presents states, icons and colours, re-implemented for the frame
 * (the HA web UI can't run on KitKat's WebView). Pure JVM code, unit tested.
 */
object HaFormat {

    // ---- state text ------------------------------------------------------------------------

    fun stateText(e: HaEntity, now: Instant = Instant.now()): String {
        when (e.state) {
            "unavailable" -> return "Unavailable"
            "unknown" -> return "Unknown"
        }
        return when (e.domain) {
            "binary_sensor" -> binarySensorText(e.state == "on", e.deviceClass)
            "sensor", "number", "input_number" -> sensorText(e, now)
            "light" -> if (e.state == "on") {
                e.attrDouble("brightness")?.let { "${(it / 255.0 * 100).roundToInt()}%" } ?: "On"
            } else "Off"
            "climate" -> climateText(e)
            "cover" -> when (e.state) {
                "open" -> e.attrDouble("current_position")?.let { "Open · ${it.roundToInt()}%" } ?: "Open"
                else -> humanize(e.state)
            }
            "person", "device_tracker" -> when (e.state) {
                "home" -> "Home"
                "not_home" -> "Away"
                else -> humanize(e.state)
            }
            "weather" -> weatherConditionText(e.state)
            "sun" -> if (e.state == "above_horizon") "Above horizon" else "Below horizon"
            "alarm_control_panel" -> humanize(e.state)
            "timer" -> humanize(e.state)
            "input_datetime", "datetime", "date", "time" -> e.state
            "update" -> if (e.state == "on") "Update available" else "Up-to-date"
            else -> humanize(e.state)
        }
    }

    private fun climateText(e: HaEntity): String {
        val mode = when (e.state) {
            "heat_cool" -> "Heat/Cool"
            "fan_only" -> "Fan only"
            else -> humanize(e.state)
        }
        val current = e.attrDouble("current_temperature") ?: return mode
        return "$mode · ${number(current)} ${temperatureUnit(e)}"
    }

    fun temperatureUnit(e: HaEntity): String = e.attrString("temperature_unit") ?: "°"

    private fun sensorText(e: HaEntity, now: Instant): String {
        if (e.deviceClass == "timestamp") {
            return parseTimestamp(e.state)?.let { relativeTime(it, now) } ?: e.state
        }
        val value = e.state.toDoubleOrNull() ?: return humanizeIfSlug(e.state)
        return withUnit(number(value), e.unit)
    }

    fun withUnit(value: String, unit: String?): String = when {
        unit.isNullOrEmpty() -> value
        unit == "%" -> "$value%"
        else -> "$value $unit"
    }

    /** Up to two decimals, trailing zeros trimmed ("21.37", "21.5", "21"). */
    fun number(value: Double): String {
        if (value == value.toLong().toDouble() && abs(value) < 1e15) return value.toLong().toString()
        val decimals = if (abs(value) >= 100) 1 else 2
        return String.format(Locale.US, "%.${decimals}f", value).trimEnd('0').trimEnd('.')
    }

    fun binarySensorText(on: Boolean, deviceClass: String?): String {
        val (onText, offText) = when (deviceClass) {
            "battery" -> "Low" to "Normal"
            "battery_charging" -> "Charging" to "Not charging"
            "carbon_monoxide", "gas", "smoke", "motion", "occupancy", "sound", "vibration" -> "Detected" to "Clear"
            "cold" -> "Cold" to "Normal"
            "connectivity" -> "Connected" to "Disconnected"
            "door", "garage_door", "opening", "window" -> "Open" to "Closed"
            "heat" -> "Hot" to "Normal"
            "light" -> "Light detected" to "No light"
            "lock" -> "Unlocked" to "Locked"
            "moisture" -> "Wet" to "Dry"
            "moving" -> "Moving" to "Not moving"
            "plug" -> "Plugged in" to "Unplugged"
            "power" -> "Power detected" to "No power"
            "presence" -> "Home" to "Away"
            "problem" -> "Problem" to "OK"
            "running" -> "Running" to "Not running"
            "safety" -> "Unsafe" to "Safe"
            "tamper" -> "Tampering detected" to "Clear"
            "update" -> "Update available" to "Up-to-date"
            else -> "On" to "Off"
        }
        return if (on) onText else offText
    }

    fun weatherConditionText(condition: String): String = when (condition) {
        "clear-night" -> "Clear, night"
        "partlycloudy" -> "Partly cloudy"
        "lightning-rainy" -> "Lightning, rainy"
        "snowy-rainy" -> "Snowy, rainy"
        "windy-variant" -> "Windy"
        else -> humanize(condition)
    }

    fun humanize(state: String): String =
        state.replace('_', ' ').replaceFirstChar { it.uppercaseChar() }

    /** Slugs like "heat_pump" read as words; free text ("Living Room TV") is left alone. */
    private fun humanizeIfSlug(state: String): String =
        if (state.matches(Regex("[a-z0-9_]+"))) humanize(state) else state

    fun parseTimestamp(value: String): Instant? = runCatching { OffsetDateTime.parse(value).toInstant() }.getOrNull()
        ?: runCatching { Instant.parse(value) }.getOrNull()

    fun relativeTime(then: Instant, now: Instant): String {
        val seconds = Duration.between(then, now).seconds
        val future = seconds < 0
        val s = abs(seconds)
        val text = when {
            s < 45 -> return if (future) "in a few seconds" else "just now"
            s < 90 -> "1 minute"
            s < 3600 -> "${(s + 30) / 60} minutes"
            s < 5400 -> "1 hour"
            s < 86400 -> "${(s + 1800) / 3600} hours"
            s < 129600 -> "1 day"
            else -> "${(s + 43200) / 86400} days"
        }
        return if (future) "in $text" else "$text ago"
    }

    // ---- active / colours ------------------------------------------------------------------

    /** Whether the entity is in an "on"-like state (drives the accent colour). */
    fun isActive(e: HaEntity): Boolean {
        if (e.isUnavailable) return false
        return when (e.domain) {
            "light", "switch", "input_boolean", "fan", "binary_sensor", "siren", "humidifier", "remote" -> e.state == "on"
            "climate" -> e.state != "off"
            "cover", "valve" -> e.state == "open" || e.state == "opening" || e.state == "closing"
            "lock" -> true
            "media_player" -> e.state == "playing" || e.state == "on" || e.state == "paused"
            "person", "device_tracker" -> e.state != "not_home"
            "alarm_control_panel" -> true
            "vacuum" -> e.state == "cleaning" || e.state == "returning"
            "sun" -> e.state == "above_horizon"
            "timer" -> e.state == "active"
            "update" -> e.state == "on"
            else -> false
        }
    }

    const val COLOR_INACTIVE = 0xFF9EA7B3.toInt()
    private const val AMBER = 0xFFFFC107.toInt()
    private const val ORANGE = 0xFFFF9800.toInt()
    const val RED = 0xFFF0626B.toInt()
    private const val GREEN = 0xFF4CC38A.toInt()
    private const val BLUE = 0xFF4BA3F5.toInt()
    private const val PURPLE = 0xFFB27CF0.toInt()
    private const val CYAN = 0xFF2BC8E0.toInt()
    private const val INDIGO = 0xFF7C8CF5.toInt()

    /** Accent colour for an entity's icon (HA's default state colours, approximately). */
    fun stateColor(e: HaEntity): Int {
        if (!isActive(e)) return COLOR_INACTIVE
        return when (e.domain) {
            "light" -> e.attr("rgb_color")?.takeIf { it.isJsonArray }?.asJsonArray?.let { rgb ->
                if (rgb.size() == 3) {
                    val r = rgb[0].asInt; val g = rgb[1].asInt; val b = rgb[2].asInt
                    // very pale colours (white bulbs) would vanish on the glass: use amber
                    if (r > 230 && g > 230 && b > 230) AMBER else (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                } else null
            } ?: AMBER
            "climate" -> when (e.state) {
                "heat" -> ORANGE
                "cool" -> BLUE
                "heat_cool", "auto" -> GREEN
                "dry" -> AMBER
                "fan_only" -> CYAN
                else -> ORANGE
            }
            "lock" -> when (e.state) {
                "locked" -> GREEN
                "jammed", "unlocked", "open" -> RED
                else -> ORANGE
            }
            "alarm_control_panel" -> when {
                e.state == "disarmed" -> COLOR_INACTIVE
                e.state == "triggered" -> RED
                e.state.startsWith("armed") -> GREEN
                else -> ORANGE
            }
            "binary_sensor" -> when (e.deviceClass) {
                "smoke", "gas", "carbon_monoxide", "moisture", "problem", "safety", "tamper", "heat" -> RED
                "door", "garage_door", "opening", "window" -> PURPLE
                "connectivity", "plug", "power", "battery_charging", "running" -> GREEN
                else -> AMBER
            }
            "cover", "valve" -> PURPLE
            "fan" -> CYAN
            "media_player" -> INDIGO
            "person", "device_tracker" -> GREEN
            "vacuum" -> CYAN
            "update" -> ORANGE
            else -> AMBER
        }
    }

    /**
     * Icon colour: the active colour when the entity is "on", otherwise a tone that says something
     * about the value (battery level, a service being up, a safety sensor being clear), and only
     * then neutral grey. HA itself greys out nearly everything that's off, which on a wall display
     * makes every card look the same.
     */
    fun iconColor(e: HaEntity): Int {
        if (e.isUnavailable) return COLOR_INACTIVE
        if (isActive(e)) return stateColor(e)
        return tone(e) ?: COLOR_INACTIVE
    }

    /** True when the entity needs attention (low battery, service down, leak or smoke detected). */
    fun isAlert(e: HaEntity): Boolean = !e.isUnavailable && (iconColor(e) == RED)

    private val GOOD_STATES = setOf("up", "online", "connected", "ok", "healthy", "ready", "running", "normal", "idle")
    private val BAD_STATES = setOf("down", "offline", "disconnected", "error", "failed", "problem", "critical", "unhealthy")
    private val SAFETY_CLASSES = setOf("smoke", "gas", "carbon_monoxide", "moisture", "problem", "safety", "tamper", "heat", "cold")

    private fun tone(e: HaEntity): Int? = when (e.domain) {
        "sensor" -> sensorTone(e)
        "binary_sensor" -> when {
            e.deviceClass in SAFETY_CLASSES -> GREEN // "off" = clear / dry / OK
            e.deviceClass == "connectivity" -> RED // "off" = disconnected
            e.deviceClass == "battery" -> GREEN // "off" = normal
            else -> null
        }
        "weather" -> AMBER
        "sun" -> INDIGO
        "humidifier" -> null
        else -> null
    }

    private fun sensorTone(e: HaEntity): Int? {
        val value = e.state.toDoubleOrNull()
        if (e.deviceClass == "battery" && value != null) {
            return when {
                value < 20 -> RED
                value < 50 -> AMBER
                else -> GREEN
            }
        }
        val lower = e.state.lowercase(Locale.US)
        if (value == null) {
            if (lower in GOOD_STATES) return GREEN
            if (lower in BAD_STATES) return RED
        }
        return when (e.deviceClass) {
            "temperature" -> ORANGE
            "humidity", "moisture", "precipitation", "precipitation_intensity", "water" -> CYAN
            "illuminance" -> AMBER
            "power", "apparent_power", "energy", "voltage", "current" -> AMBER
            "carbon_dioxide", "volatile_organic_compounds", "pm25", "pm10", "pm1", "aqi" -> GREEN
            "pressure", "atmospheric_pressure", "wind_speed", "speed" -> BLUE
            else -> null
        }
    }

    /** A named colour from card config ("red", "amber", "#ff0000"), or null. */
    fun namedColor(name: String?): Int? {
        if (name.isNullOrBlank()) return null
        if (name.startsWith("#") && (name.length == 7 || name.length == 9)) {
            return runCatching {
                val v = name.substring(1).toLong(16)
                if (name.length == 7) (0xFF000000 or v).toInt() else v.toInt()
            }.getOrNull()
        }
        return when (name) {
            "primary", "accent", "blue" -> BLUE
            "red", "pink" -> RED
            "purple", "deep-purple" -> PURPLE
            "indigo" -> INDIGO
            "light-blue", "cyan", "teal" -> CYAN
            "green", "light-green", "lime" -> GREEN
            "yellow", "amber", "state" -> AMBER
            "orange", "deep-orange" -> ORANGE
            "brown" -> 0xFFA1887F.toInt()
            "grey", "blue-grey", "disabled", "dark-grey", "black", "white" -> COLOR_INACTIVE
            else -> null
        }
    }

    // ---- icons -----------------------------------------------------------------------------

    /** MDI icon name (without "mdi:") for an entity: its own icon attribute, else HA's defaults. */
    fun iconName(e: HaEntity, override: String? = null): String {
        val explicit = override ?: e.attrString("icon")
        explicit?.let { stripIconPrefix(it) }?.let { return it }
        return defaultIcon(e)
    }

    fun stripIconPrefix(icon: String): String? = when {
        icon.startsWith("mdi:") -> icon.removePrefix("mdi:")
        icon.startsWith("hass:") -> icon.removePrefix("hass:")
        else -> null
    }

    private fun defaultIcon(e: HaEntity): String {
        val on = e.state == "on"
        return when (e.domain) {
            "light" -> if (on) "lightbulb" else "lightbulb-outline"
            "switch" -> if (on) "toggle-switch-variant" else "toggle-switch-variant-off"
            "input_boolean" -> if (on) "check-circle-outline" else "close-circle-outline"
            "fan" -> if (on) "fan" else "fan-off"
            "binary_sensor" -> binarySensorIcon(on, e.deviceClass)
            "sensor" -> sensorIcon(e)
            "climate" -> "thermostat"
            "water_heater" -> "water-boiler"
            "humidifier" -> if (on) "air-humidifier" else "air-humidifier-off"
            "cover" -> coverIcon(e)
            "valve" -> if (e.state == "open") "valve-open" else "valve-closed"
            "lock" -> when (e.state) {
                "unlocked", "open" -> "lock-open-variant"
                "jammed" -> "lock-alert"
                else -> "lock"
            }
            "media_player" -> when (e.deviceClass) {
                "tv" -> if (on || e.state == "playing") "television-play" else "television"
                "speaker" -> "speaker"
                "receiver" -> "audio-video"
                else -> if (e.state == "playing") "cast-connected" else "cast"
            }
            "person" -> if (e.state == "not_home") "account-arrow-right" else "account"
            "device_tracker" -> if (e.state == "not_home") "account-arrow-right" else "account"
            "sun" -> if (e.state == "above_horizon") "white-balance-sunny" else "weather-night"
            "weather" -> weatherIcon(e.state)
            "camera" -> "video"
            "vacuum" -> "robot-vacuum"
            "scene" -> "palette"
            "script" -> "script-text"
            "automation" -> if (on) "robot" else "robot-off"
            "alarm_control_panel" -> when {
                e.state == "disarmed" -> "shield-off"
                e.state == "triggered" -> "bell-ring"
                e.state == "armed_away" -> "shield-lock"
                e.state == "armed_night" -> "shield-moon"
                else -> "shield-home"
            }
            "update" -> if (on) "package-up" else "package"
            "button", "input_button" -> "gesture-tap-button"
            "number", "input_number" -> "ray-vertex"
            "select", "input_select" -> "format-list-bulleted"
            "input_text", "text" -> "form-textbox"
            "input_datetime", "datetime" -> "calendar-clock"
            "date" -> "calendar"
            "time" -> "clock"
            "timer" -> "timer-outline"
            "counter" -> "counter"
            "zone" -> "map-marker-radius"
            "calendar" -> "calendar"
            "todo" -> "clipboard-list"
            "siren" -> "bullhorn"
            "remote" -> "remote"
            "event" -> "eye-check"
            "image" -> "image"
            "lawn_mower" -> "robot-mower"
            else -> "bookmark"
        }
    }

    private fun binarySensorIcon(on: Boolean, deviceClass: String?): String = when (deviceClass) {
        "battery" -> if (on) "battery-outline" else "battery"
        "battery_charging" -> if (on) "battery-charging" else "battery"
        "carbon_monoxide", "smoke" -> if (on) "smoke-detector-alert" else "smoke-detector"
        "cold" -> if (on) "snowflake" else "thermometer"
        "connectivity" -> if (on) "check-network-outline" else "close-network-outline"
        "door" -> if (on) "door-open" else "door-closed"
        "garage_door" -> if (on) "garage-open" else "garage"
        "gas" -> if (on) "alert-circle" else "checkbox-marked-circle-outline"
        "heat" -> if (on) "fire" else "thermometer"
        "light" -> if (on) "brightness-7" else "brightness-5"
        "lock" -> if (on) "lock-open-variant" else "lock"
        "moisture" -> if (on) "water" else "water-off"
        "motion" -> if (on) "motion-sensor" else "motion-sensor-off"
        "occupancy", "presence" -> if (on) "home" else "home-outline"
        "opening" -> if (on) "square-outline" else "square"
        "plug", "power" -> if (on) "power-plug" else "power-plug-off"
        "problem", "safety" -> if (on) "alert-circle" else "check-circle"
        "running" -> if (on) "play" else "stop"
        "sound" -> if (on) "music-note" else "music-note-off"
        "tamper" -> if (on) "shield-alert" else "shield-check"
        "update" -> if (on) "package-up" else "package"
        "vibration" -> if (on) "vibrate" else "crop-portrait"
        "window" -> if (on) "window-open" else "window-closed"
        else -> if (on) "checkbox-marked-circle" else "radiobox-blank"
    }

    private fun sensorIcon(e: HaEntity): String = when (e.deviceClass) {
        "temperature" -> "thermometer"
        "humidity", "moisture" -> "water-percent"
        "battery" -> batteryIcon(e.state.toDoubleOrNull())
        "power", "apparent_power", "reactive_power" -> "flash"
        "energy" -> "lightning-bolt"
        "voltage" -> "sine-wave"
        "current" -> "current-ac"
        "illuminance" -> "brightness-5"
        "pressure", "atmospheric_pressure" -> "gauge"
        "carbon_dioxide" -> "molecule-co2"
        "carbon_monoxide" -> "molecule-co"
        "pm25", "pm10", "pm1" -> "blur"
        "aqi" -> "air-filter"
        "timestamp" -> "clock"
        "date" -> "calendar"
        "duration" -> "progress-clock"
        "signal_strength" -> "wifi"
        "speed", "wind_speed" -> "speedometer"
        "distance" -> "arrow-left-right"
        "gas" -> "meter-gas"
        "water" -> "water"
        "monetary" -> "cash"
        "precipitation", "precipitation_intensity" -> "weather-rainy"
        "volatile_organic_compounds", "volatile_organic_compounds_parts" -> "molecule"
        else -> when {
            e.unit == "°C" || e.unit == "°F" -> "thermometer"
            else -> "eye"
        }
    }

    fun batteryIcon(level: Double?): String {
        if (level == null) return "battery-unknown"
        val tens = ((level + 5) / 10).toInt() * 10
        return when {
            tens >= 100 -> "battery"
            tens <= 0 -> "battery-outline"
            else -> "battery-$tens"
        }
    }

    private fun coverIcon(e: HaEntity): String {
        val open = e.state == "open" || e.state == "opening"
        return when (e.deviceClass) {
            "garage" -> if (open) "garage-open" else "garage"
            "door" -> if (open) "door-open" else "door-closed"
            "gate" -> if (open) "gate-open" else "gate"
            "window" -> if (open) "window-open" else "window-closed"
            "blind", "curtain" -> if (open) "blinds-open" else "blinds"
            else -> if (open) "window-shutter-open" else "window-shutter"
        }
    }

    fun weatherIcon(condition: String): String = when (condition) {
        "clear-night" -> "weather-night"
        "cloudy" -> "weather-cloudy"
        "exceptional" -> "alert-circle-outline"
        "fog" -> "weather-fog"
        "hail" -> "weather-hail"
        "lightning" -> "weather-lightning"
        "lightning-rainy" -> "weather-lightning-rainy"
        "partlycloudy" -> "weather-partly-cloudy"
        "pouring" -> "weather-pouring"
        "rainy" -> "weather-rainy"
        "snowy" -> "weather-snowy"
        "snowy-rainy" -> "weather-snowy-rainy"
        "sunny" -> "weather-sunny"
        "windy" -> "weather-windy"
        "windy-variant" -> "weather-windy-variant"
        else -> "weather-cloudy"
    }
}
