package nl.giejay.android.tv.immich.homeassistant

import com.google.common.truth.Truth.assertThat
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Test
import java.time.Instant

class HaFormatTest {
    private fun entity(id: String, state: String, attrs: String = "{}") =
        HaEntity(id, state, JsonParser.parseString(attrs).asJsonObject, 0.0)

    @Test
    fun sensorValuesWithUnits() {
        assertThat(HaFormat.stateText(entity("sensor.t", "21.37", """{"unit_of_measurement":"°C"}"""))).isEqualTo("21.37 °C")
        assertThat(HaFormat.stateText(entity("sensor.t", "21.0", """{"unit_of_measurement":"°C"}"""))).isEqualTo("21 °C")
        assertThat(HaFormat.stateText(entity("sensor.h", "48", """{"unit_of_measurement":"%"}"""))).isEqualTo("48%")
        assertThat(HaFormat.stateText(entity("sensor.p", "1234.5678", """{"unit_of_measurement":"W"}"""))).isEqualTo("1234.6 W")
        assertThat(HaFormat.stateText(entity("sensor.mode", "heat_pump"))).isEqualTo("Heat pump")
        assertThat(HaFormat.stateText(entity("sensor.name", "Living Room TV"))).isEqualTo("Living Room TV")
    }

    @Test
    fun unavailableAndUnknown() {
        assertThat(HaFormat.stateText(entity("light.x", "unavailable"))).isEqualTo("Unavailable")
        assertThat(HaFormat.stateText(entity("sensor.x", "unknown"))).isEqualTo("Unknown")
        assertThat(HaFormat.isActive(entity("light.x", "unavailable"))).isFalse()
    }

    @Test
    fun binarySensorDeviceClasses() {
        assertThat(HaFormat.stateText(entity("binary_sensor.d", "on", """{"device_class":"door"}"""))).isEqualTo("Open")
        assertThat(HaFormat.stateText(entity("binary_sensor.d", "off", """{"device_class":"door"}"""))).isEqualTo("Closed")
        assertThat(HaFormat.stateText(entity("binary_sensor.m", "on", """{"device_class":"motion"}"""))).isEqualTo("Detected")
        assertThat(HaFormat.stateText(entity("binary_sensor.x", "off"))).isEqualTo("Off")
        assertThat(HaFormat.iconName(entity("binary_sensor.d", "on", """{"device_class":"door"}"""))).isEqualTo("door-open")
    }

    @Test
    fun lightsAndClimate() {
        assertThat(HaFormat.stateText(entity("light.k", "on", """{"brightness":128}"""))).isEqualTo("50%")
        assertThat(HaFormat.stateText(entity("light.k", "on"))).isEqualTo("On")
        assertThat(HaFormat.stateText(entity("light.k", "off"))).isEqualTo("Off")
        assertThat(HaFormat.stateText(entity("climate.h", "heat", """{"current_temperature":20.5,"temperature_unit":"°C"}""")))
            .isEqualTo("Heat · 20.5 °C")
        assertThat(HaFormat.stateColor(entity("climate.h", "cool"))).isNotEqualTo(HaFormat.COLOR_INACTIVE)
        assertThat(HaFormat.stateColor(entity("light.k", "off"))).isEqualTo(HaFormat.COLOR_INACTIVE)
        // a white bulb would vanish on the glass: falls back to amber
        assertThat(HaFormat.stateColor(entity("light.k", "on", """{"rgb_color":[255,255,255]}""")))
            .isEqualTo(HaFormat.stateColor(entity("light.k", "on")))
        assertThat(HaFormat.stateColor(entity("light.k", "on", """{"rgb_color":[255,0,0]}"""))).isEqualTo(0xFFFF0000.toInt())
    }

    @Test
    fun iconsPreferExplicitThenDefaults() {
        assertThat(HaFormat.iconName(entity("light.k", "on", """{"icon":"mdi:ceiling-light"}"""))).isEqualTo("ceiling-light")
        assertThat(HaFormat.iconName(entity("light.k", "on"), override = "mdi:lamp")).isEqualTo("lamp")
        assertThat(HaFormat.iconName(entity("light.k", "off"))).isEqualTo("lightbulb-outline")
        assertThat(HaFormat.iconName(entity("sensor.b", "47", """{"device_class":"battery"}"""))).isEqualTo("battery-50")
        assertThat(HaFormat.iconName(entity("sensor.t", "20", """{"unit_of_measurement":"°C"}"""))).isEqualTo("thermometer")
        assertThat(HaFormat.iconName(entity("weather.home", "partlycloudy"))).isEqualTo("weather-partly-cloudy")
        assertThat(HaFormat.iconName(entity("person.g", "not_home"))).isEqualTo("account-arrow-right")
    }

    @Test
    fun peopleAndTimestamps() {
        assertThat(HaFormat.stateText(entity("person.g", "home"))).isEqualTo("Home")
        assertThat(HaFormat.stateText(entity("person.g", "not_home"))).isEqualTo("Away")
        val now = Instant.parse("2026-11-16T15:00:00Z")
        val ts = entity("sensor.last", "2026-11-16T14:55:00+00:00", """{"device_class":"timestamp"}""")
        assertThat(HaFormat.stateText(ts, now)).isEqualTo("5 minutes ago")
        assertThat(HaFormat.relativeTime(Instant.parse("2026-11-16T17:00:00Z"), now)).isEqualTo("in 2 hours")
        assertThat(HaFormat.relativeTime(Instant.parse("2026-11-16T14:59:50Z"), now)).isEqualTo("just now")
    }

    @Test
    fun namedColors() {
        assertThat(HaFormat.namedColor("#ff0000")).isEqualTo(0xFFFF0000.toInt())
        assertThat(HaFormat.namedColor("amber")).isNotNull()
        assertThat(HaFormat.namedColor("nonsense")).isNull()
        assertThat(HaFormat.namedColor(null)).isNull()
    }

    @Test
    fun friendlyNameFallsBackToObjectId() {
        assertThat(entity("sensor.living_room_temp", "1").friendlyName).isEqualTo("Living room temp")
        assertThat(HaEntity("sensor.x", "1", JsonObject().apply { addProperty("friendly_name", "Outside") }, 0.0).friendlyName)
            .isEqualTo("Outside")
    }
}
