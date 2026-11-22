package nl.giejay.android.tv.immich.homeassistant

import com.google.common.truth.Truth.assertThat
import com.google.gson.JsonParser
import org.junit.Test
import java.time.Instant

class AmbientFormatTest {
    private fun entity(id: String, state: String, attrs: String = "{}") =
        HaEntity(id, state, JsonParser.parseString(attrs).asJsonObject, 0.0)

    @Test
    fun temperatures() {
        assertThat(AmbientFormat.temperature(56.6, 0)).isEqualTo("57°")
        assertThat(AmbientFormat.temperature(67.54, 1)).isEqualTo("67.5°")
        assertThat(AmbientFormat.temperature(67.0, 1)).isEqualTo("67°")
        assertThat(AmbientFormat.temperature(-3.2, 0)).isEqualTo("−3°")
        assertThat(AmbientFormat.temperature(-0.2, 0)).isEqualTo("0°")
    }

    @Test
    fun shortRelativeTimes() {
        val now = Instant.parse("2026-11-22T12:00:00Z")
        assertThat(AmbientFormat.ago(now.minusSeconds(10), now)).isEqualTo("just now")
        assertThat(AmbientFormat.ago(now.minusSeconds(60), now)).isEqualTo("1 min ago")
        assertThat(AmbientFormat.ago(now.minusSeconds(12 * 60), now)).isEqualTo("12 min ago")
        assertThat(AmbientFormat.ago(now.minusSeconds(4 * 3600), now)).isEqualTo("4 h ago")
        assertThat(AmbientFormat.ago(now.minusSeconds(20 * 3600), now)).isEqualTo("20 h ago")
        assertThat(AmbientFormat.ago(now.minusSeconds(3 * 86400), now)).isEqualTo("3 d ago")
        assertThat(AmbientFormat.rang(now.minusSeconds(5), now)).isEqualTo("Rang just now")
        assertThat(AmbientFormat.rang(null, now)).isEqualTo("Rang")
    }

    @Test
    fun suffixes() {
        assertThat(AmbientFormat.stripSuffix("Office Climate Temperature", "temperature")).isEqualTo("Office Climate")
        assertThat(AmbientFormat.stripSuffix("Basement Leak Sensor", "leak sensor")).isEqualTo("Basement")
        assertThat(AmbientFormat.stripSuffix("Temperature", "temperature")).isEqualTo("Temperature")
    }

    @Test
    fun thermostatDetails() {
        assertThat(AmbientFormat.thermostatDetail(entity("climate.t", "heat", """{"temperature": 67, "hvac_action": "idle"}""")))
            .isEqualTo("Heat to 67° · idle")
        assertThat(AmbientFormat.thermostatDetail(entity("climate.t", "cool", """{"temperature": 74.5, "hvac_action": "cooling"}""")))
            .isEqualTo("Cool to 74.5° · cooling")
        assertThat(AmbientFormat.thermostatDetail(entity("climate.t", "heat_cool", """{"target_temp_low": 66, "target_temp_high": 74}""")))
            .isEqualTo("66–74°")
        assertThat(AmbientFormat.thermostatDetail(entity("climate.t", "off"))).isEqualTo("Off")
        assertThat(AmbientFormat.thermostatDetail(entity("climate.t", "unavailable"))).isEqualTo("Unavailable")
    }

    @Test
    fun capsUseARootLocale() {
        assertThat(AmbientFormat.caps("both home · 67.5° inside")).isEqualTo("BOTH HOME · 67.5° INSIDE")
    }
}
