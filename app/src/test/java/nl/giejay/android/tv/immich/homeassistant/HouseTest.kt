package nl.giejay.android.tv.immich.homeassistant

import com.google.common.truth.Truth.assertThat
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class HouseTest {
    private fun entity(id: String, state: String, attrs: String = "{}", lastChanged: Double = 0.0) =
        HaEntity(id, state, JsonParser.parseString(attrs).asJsonObject, lastChanged)

    private fun house(refs: List<DashboardRef>, vararg entities: HaEntity, devices: Map<String, String> = emptyMap()) =
        House(refs, entities.associateBy { it.id }, devices)

    private fun refs(vararg ids: String) = ids.mapIndexed { i, id -> DashboardRef(id, null, i) }

    // ---- dashboard scan -----------------------------------------------------------------------

    @Test
    fun scanFindsEntitiesInSectionsBadgesAndSubButtonsWithTheirCardNames() {
        val view = JsonParser.parseString(
            """
            {"badges": [{"type": "entity", "entity": "person.rachel"}],
             "sections": [
               {"cards": [
                 {"type": "heading", "heading": "Outside"},
                 {"type": "weather-forecast", "entity": "weather.home"},
                 {"type": "custom:bubble-card", "card_type": "button", "entity": "sensor.main_floor_temperature", "name": "Main floor",
                  "sub_button": [{"entity": "sensor.main_floor_humidity", "name": "{{ states('x') }}"}]}
               ]},
               {"cards": [{"type": "entities", "entities": ["binary_sensor.basement_leak", {"entity": "sensor.office_t", "name": "Office"}]}]}
             ]}
            """
        ).asJsonObject

        val found = DashboardScan.scan(view)

        assertThat(found.map { it.entityId }).containsExactly(
            "person.rachel", "weather.home", "sensor.main_floor_temperature", "sensor.main_floor_humidity",
            "binary_sensor.basement_leak", "sensor.office_t"
        ).inOrder()
        assertThat(found.first { it.entityId == "sensor.main_floor_temperature" }.name).isEqualTo("Main floor")
        // templates are not names
        assertThat(found.first { it.entityId == "sensor.main_floor_humidity" }.name).isNull()
        // the temperature and its humidity sub-button share a card
        assertThat(found[2].card).isEqualTo(found[3].card)
        assertThat(found[4].card).isNotEqualTo(found[2].card)
    }

    @Test
    fun scanIgnoresThingsThatAreNotEntityIds() {
        val view = JsonParser.parseString("""{"cards": [{"type": "markdown", "entity": "not an id"}, {"entity": "light.ok"}]}""").asJsonObject
        assertThat(DashboardScan.scan(view).map { it.entityId }).containsExactly("light.ok")
    }

    @Test
    fun dashboardSettingAndViewPicking() {
        assertThat(DashboardScan.parseSetting("picture-frame")).isEqualTo("picture-frame" to null)
        assertThat(DashboardScan.parseSetting("/dashboard-house/home/")).isEqualTo("dashboard-house" to "home")
        assertThat(DashboardScan.parseSetting("lovelace")).isEqualTo(null to null)
        val config = JsonParser.parseString("""{"views": [{"path": "a", "title": "A"}, {"path": "b", "title": "B"}]}""").asJsonObject
        assertThat(DashboardScan.pickView(config, "b")?.get("title")?.asString).isEqualTo("B")
        assertThat(DashboardScan.pickView(config, "1")?.get("title")?.asString).isEqualTo("B")
        assertThat(DashboardScan.pickView(config, null)?.get("title")?.asString).isEqualTo("A")
        assertThat(DashboardScan.pickView(JsonObject(), null)).isNull()
    }

    // ---- notifications ------------------------------------------------------------------------

    @Test
    fun noticesAreAlertsWithTheEntitysOwnIconSafetyFirstThenHaNotifications() {
        val h = house(
            refs("sensor.lock_battery", "binary_sensor.dehumidifier_filter", "binary_sensor.basement_leak", "light.lamp", "sensor.ok_battery"),
            entity("sensor.lock_battery", "12", """{"device_class":"battery","friendly_name":"Front Door Lock Battery","unit_of_measurement":"%"}"""),
            entity("binary_sensor.dehumidifier_filter", "on", """{"device_class":"problem","friendly_name":"Clean the dehumidifier filter","icon":"mdi:air-filter"}"""),
            entity("binary_sensor.basement_leak", "on", """{"device_class":"moisture","friendly_name":"Basement Leak Sensor"}"""),
            entity("light.lamp", "on"),
            entity("sensor.ok_battery", "80", """{"device_class":"battery"}""")
        )

        val notices = h.notices(listOf(PersistentNotice("n1", "Furnace filter due", null), PersistentNotice("n2", null, "Line one\nmore")))

        assertThat(notices.map { it.label }).containsExactly(
            "Basement Leak Sensor · Wet",
            "Front Door Lock Battery · 12%",
            "Clean the dehumidifier filter",
            "Furnace filter due",
            "Line one"
        ).inOrder()
        assertThat(notices.first { it.key == "binary_sensor.dehumidifier_filter" }.icon).isEqualTo("air-filter")
        assertThat(notices.first { it.key == "binary_sensor.basement_leak" }.icon).isEqualTo("water")
        assertThat(notices.first { it.key == "persistent:n1" }.icon).isEqualTo(House.GENERIC_ALERT_ICON)
    }

    @Test
    fun aDashboardNameOverridesTheFriendlyName() {
        val h = house(
            listOf(DashboardRef("binary_sensor.x", "Dehumidifier tank", 0)),
            entity("binary_sensor.x", "on", """{"device_class":"problem","friendly_name":"Dehumidifier Tank Full"}""")
        )
        assertThat(h.notices(emptyList()).single().label).isEqualTo("Dehumidifier tank")
    }

    // ---- presence -----------------------------------------------------------------------------

    @Test
    fun presenceLabels() {
        val ga = entity("person.ga", "home", """{"friendly_name":"Georges-Antoine Assi"}""")
        val rachel = entity("person.rachel", "home", """{"friendly_name":"Rachel"}""")
        val awayRachel = entity("person.rachel", "not_home", """{"friendly_name":"Rachel"}""")

        assertThat(House.presenceOf(listOf(ga, rachel))?.label).isEqualTo("Both home")
        assertThat(House.presenceOf(listOf(ga, rachel))?.names).isEqualTo("Georges-Antoine Assi, Rachel")
        assertThat(House.presenceOf(listOf(ga, awayRachel))?.label).isEqualTo("Georges-Antoine home")
        assertThat(House.presenceOf(listOf(entity("person.a", "not_home"), awayRachel))?.label).isEqualTo("Nobody home")
        assertThat(House.presenceOf(listOf(ga))?.label).isEqualTo("Georges-Antoine home")
        assertThat(House.presenceOf(listOf(ga, rachel, entity("person.c", "home", """{"friendly_name":"C"}""")))?.label).isEqualTo("Everyone home")
        assertThat(House.presenceOf(emptyList())).isNull()
        assertThat(House.presenceOf(listOf(entity("person.a", "unavailable")))).isNull()
    }

    // ---- screen 1 -----------------------------------------------------------------------------

    @Test
    fun outdoorAndIndoorTemperatures() {
        val h = house(
            refs("weather.home", "climate.nest", "sensor.main_floor_temperature"),
            entity("weather.home", "partlycloudy", """{"temperature": 56.6}"""),
            entity("climate.nest", "heat", """{"current_temperature": 67, "temperature": 67, "hvac_action": "idle"}"""),
            entity("sensor.main_floor_temperature", "67.54", """{"device_class":"temperature","unit_of_measurement":"°F"}""")
        )
        assertThat(h.outsideNow()).isEqualTo("weather-partly-cloudy" to "57°")
        assertThat(h.indoorTemperature()).isEqualTo("67.5°")
        // with no room sensor, the thermostat's reading
        val noSensor = house(refs("climate.nest"), entity("climate.nest", "heat", """{"current_temperature": 66.0}"""))
        assertThat(noSensor.indoorTemperature()).isEqualTo("66°")
        assertThat(house(refs("weather.home"), entity("weather.home", "unavailable")).outsideNow()).isNull()
    }

    @Test
    fun outdoorIsShownInCelsiusWhileIndoorStaysInFahrenheit() {
        val h = house(
            refs("weather.home", "sensor.main_floor_temperature"),
            entity("weather.home", "partlycloudy", """{"temperature": 57, "temperature_unit": "°F"}"""),
            entity("sensor.main_floor_temperature", "67.54", """{"device_class":"temperature","unit_of_measurement":"°F"}""")
        )
        assertThat(h.outsideNow()).isEqualTo("weather-partly-cloudy" to "14°")
        assertThat(h.indoorTemperature()).isEqualTo("67.5°")

        val forecast = JsonArray()
        listOf("2026-11-22" to (58.0 to 49.0), "2026-11-23" to (51.0 to 28.0)).forEach { (day, t) ->
            forecast.add(JsonObject().apply {
                addProperty("datetime", "${day}T12:00:00+00:00")
                addProperty("temperature", t.first)
                addProperty("templow", t.second)
            })
        }
        val outside = h.outside(forecast, LocalDate.of(2026, 11, 22), ZoneId.of("America/Toronto"))!!
        assertThat(outside.temperature).isEqualTo("14°")
        assertThat(outside.high).isEqualTo("14°")
        assertThat(outside.low).isEqualTo("9°")
        assertThat(outside.days.first()).isEqualTo(ForecastDay("Mon", "11°", "−2°"))

        // a weather entity already in Celsius is left alone
        val metric = house(refs("weather.home"), entity("weather.home", "sunny", """{"temperature": 14.2, "temperature_unit": "°C"}"""))
        assertThat(metric.outsideNow()!!.second).isEqualTo("14°")
    }

    // ---- glance -------------------------------------------------------------------------------

    @Test
    fun statusLinesForPresenceSmokeAndLeaks() {
        val clear = house(
            refs("person.a", "person.b", "binary_sensor.smoke", "binary_sensor.co", "binary_sensor.leak"),
            entity("person.a", "home"), entity("person.b", "home"),
            entity("binary_sensor.smoke", "off", """{"device_class":"smoke"}"""),
            entity("binary_sensor.co", "off", """{"device_class":"carbon_monoxide"}"""),
            entity("binary_sensor.leak", "off", """{"device_class":"moisture","friendly_name":"Basement Leak Sensor"}""")
        )
        assertThat(clear.statusLines()).containsExactly(
            SafetyLine("Both home", true), SafetyLine("Smoke & CO clear", true), SafetyLine("No leaks", true)
        ).inOrder()

        val alarm = house(
            refs("binary_sensor.smoke", "binary_sensor.leak"),
            entity("binary_sensor.smoke", "on", """{"device_class":"smoke"}"""),
            entity("binary_sensor.leak", "on", """{"device_class":"moisture","friendly_name":"Basement Leak Sensor"}""")
        )
        assertThat(alarm.statusLines()).containsExactly(SafetyLine("Smoke detected", false), SafetyLine("Leak: Basement", false)).inOrder()
    }

    @Test
    fun roomsStartWithTheThermostatAndPairHumidityByCardOrDevice() {
        val h = house(
            listOf(
                DashboardRef("climate.nest", null, 0),
                DashboardRef("sensor.main_t", "Main floor", 1),
                DashboardRef("sensor.main_h", null, 1),
                DashboardRef("sensor.office_climate_temperature", null, 2),
                DashboardRef("sensor.office_climate_humidity", null, 3)
            ),
            entity("climate.nest", "heat", """{"current_temperature": 67, "temperature": 67, "hvac_action": "idle"}"""),
            entity("sensor.main_t", "67.5", """{"device_class":"temperature"}"""),
            entity("sensor.main_h", "54", """{"device_class":"humidity"}"""),
            entity("sensor.office_climate_temperature", "64.9", """{"device_class":"temperature","friendly_name":"Office Climate Temperature"}"""),
            entity("sensor.office_climate_humidity", "57.2", """{"device_class":"humidity"}"""),
            devices = mapOf("sensor.office_climate_temperature" to "d1", "sensor.office_climate_humidity" to "d1")
        )
        assertThat(h.rooms()).containsExactly(
            RoomRow("climate.nest", "Thermostat", "Heat to 67° · idle", "67°"),
            RoomRow("sensor.main_t", "Main floor", "Humidity 54%", "67.5°"),
            RoomRow("sensor.office_climate_temperature", "Office", "Humidity 57%", "64.9°")
        ).inOrder()
        assertThat(h.rooms(max = 2)).hasSize(2)
    }

    @Test
    fun outsideTakesTodaysHighLowAndTheNextFourDays() {
        val h = house(refs("weather.home"), entity("weather.home", "rainy", """{"temperature": 57}"""))
        val forecast = JsonArray()
        listOf("2026-11-22" to (58.0 to 49.0), "2026-11-23" to (51.0 to 39.0), "2026-11-24" to (38.0 to 30.0),
            "2026-11-25" to (35.0 to 28.0), "2026-11-26" to (37.0 to 27.0), "2026-11-27" to (40.0 to 30.0)).forEach { (day, t) ->
            forecast.add(JsonObject().apply {
                addProperty("datetime", "${day}T12:00:00+00:00")
                addProperty("temperature", t.first)
                addProperty("templow", t.second)
            })
        }

        val outside = h.outside(forecast, LocalDate.of(2026, 11, 22), ZoneId.of("America/Toronto"))!!

        assertThat(outside.icon).isEqualTo("weather-rainy")
        assertThat(outside.temperature).isEqualTo("57°")
        assertThat(outside.high).isEqualTo("58°")
        assertThat(outside.low).isEqualTo("49°")
        assertThat(outside.days.map { it.day }).containsExactly("Mon", "Tue", "Wed", "Thu").inOrder()
        assertThat(outside.days.first()).isEqualTo(ForecastDay("Mon", "51°", "39°"))
        // no forecast yet: still the current conditions
        assertThat(h.outside(null, LocalDate.of(2026, 11, 22), ZoneId.of("UTC"))!!.days).isEmpty()
    }

    @Test
    fun frontDoorAndTheDoorbellsDevice() {
        val now = Instant.parse("2026-11-22T19:41:00Z")
        val h = house(
            refs("event.door_chime"),
            entity("event.door_chime", "2026-11-21T23:41:00.000+00:00", """{"device_class":"doorbell"}"""),
            entity("event.door_motion", "2026-11-22T15:40:00.000+00:00", """{"device_class":"motion","event_type":"camera_person"}"""),
            entity("camera.door", "streaming", """{"friendly_name":"Entryway Doorbell"}"""),
            devices = mapOf("event.door_chime" to "nest", "event.door_motion" to "nest", "camera.door" to "nest", "camera.other" to "x")
        )
        // the motion event and camera aren't on the dashboard: found through the doorbell's device
        assertThat(h.motion?.id).isEqualTo("event.door_motion")
        assertThat(h.doorbellCamera()?.id).isEqualTo("camera.door")
        assertThat(h.doorbellSiblings()).containsExactly("camera.door", "event.door_chime", "event.door_motion").inOrder()
        assertThat(h.frontDoor(now)).containsExactly("Doorbell" to "20 h ago", "Motion" to "4 h ago").inOrder()
    }

    @Test
    fun noDoorbellMeansNoFrontDoorRows() {
        assertThat(house(emptyList()).frontDoor(Instant.now())).isEmpty()
        assertThat(house(emptyList()).doorbellCamera()).isNull()
    }
}
