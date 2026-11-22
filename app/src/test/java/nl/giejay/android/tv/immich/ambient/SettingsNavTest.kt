package nl.giejay.android.tv.immich.ambient

import com.google.common.truth.Truth.assertThat
import nl.giejay.android.tv.immich.shared.prefs.NumberFormat
import nl.giejay.android.tv.immich.shared.prefs.TextEditor
import org.junit.Test

class SettingsNavTest {
    // Server 2 rows, Slideshow 17, Brightness 7, Android settings 1; 7 rows fit
    private val nav = SettingsNav(listOf(2, 17, 7, 1), visibleRows = 7)

    @Test
    fun upAndDownMoveThroughTheSectionsAndStopAtTheEnds() {
        var f = nav.start()
        assertThat(nav.up(f)).isEqualTo(f)
        f = nav.down(f)
        assertThat(f).isEqualTo(SettingsFocus(section = 1))
        f = nav.down(nav.down(nav.down(f)))
        assertThat(f.section).isEqualTo(3)
    }

    @Test
    fun rightEntersTheRowsAndLeftComesBackToTheSameSection() {
        val f = nav.right(nav.down(nav.start()))
        assertThat(f).isEqualTo(SettingsFocus(section = 1, inRows = true, row = 0))
        assertThat(nav.right(f)).isEqualTo(f) // nothing further right
        assertThat(nav.left(f)).isEqualTo(SettingsFocus(section = 1, inRows = false))
        assertThat(nav.left(nav.left(f))).isEqualTo(SettingsFocus(section = 1))
    }

    @Test
    fun leavingAndReenteringASectionKeepsTheRow() {
        var f = nav.right(nav.start(1))
        repeat(3) { f = nav.down(f) }
        val back = nav.right(nav.left(f))
        assertThat(back.row).isEqualTo(3)
    }

    @Test
    fun movingToAnotherSectionStartsAtItsFirstRow() {
        var f = nav.right(nav.start(1))
        repeat(9) { f = nav.down(f) }
        f = nav.down(nav.left(f))
        assertThat(f).isEqualTo(SettingsFocus(section = 2))
        assertThat(nav.right(f).row).isEqualTo(0)
    }

    @Test
    fun longSectionsScrollByOneRow() {
        var f = nav.right(nav.start(1))
        repeat(6) { f = nav.down(f) }
        assertThat(f.row).isEqualTo(6)
        assertThat(f.firstRow).isEqualTo(0) // the seventh row still fits
        f = nav.down(f)
        assertThat(f.row).isEqualTo(7)
        assertThat(f.firstRow).isEqualTo(1)
        repeat(20) { f = nav.down(f) }
        assertThat(f.row).isEqualTo(16)
        assertThat(f.firstRow).isEqualTo(10)
        repeat(9) { f = nav.up(f) }
        assertThat(f.row).isEqualTo(7)
        assertThat(f.firstRow).isEqualTo(7) // scrolled back up as the focus reached the top
    }

    @Test
    fun backFromTheRowsGoesToTheSectionsAndFromTheSectionsLeaves() {
        val inRows = nav.right(nav.start(2))
        val sections = nav.back(inRows)
        assertThat(sections).isEqualTo(SettingsFocus(section = 2))
        assertThat(nav.back(sections!!)).isNull()
    }

    @Test
    fun aSectionWithoutRowsCannotBeEntered() {
        val empty = SettingsNav(listOf(0, 3), 7)
        assertThat(empty.right(empty.start())).isEqualTo(SettingsFocus())
        assertThat(empty.down(empty.start()).section).isEqualTo(1)
    }

    @Test
    fun startClampsTheSection() {
        assertThat(nav.start(9).section).isEqualTo(3)
        assertThat(SettingsNav(emptyList(), 7).start(2).section).isEqualTo(0)
    }

    @Test
    fun optionListOpensOnTheCurrentValueScrolledIntoView() {
        val list = OptionList(16, 7)
        assertThat(list.open(11)).isEqualTo(OptionFocus(11, 5))
        assertThat(list.open(2)).isEqualTo(OptionFocus(2, 0))
        assertThat(list.open(-1)).isEqualTo(OptionFocus(0, 0)) // the value is none of the options
    }

    @Test
    fun optionListMovesAndStopsAtTheEnds() {
        val list = OptionList(9, 7)
        var f = list.open(0)
        f = list.move(f, -1)
        assertThat(f).isEqualTo(OptionFocus(0, 0))
        repeat(12) { f = list.move(f, 1) }
        assertThat(f).isEqualTo(OptionFocus(8, 2))
    }
}

class SettingsFormatTest {

    @Test
    fun booleansAreOnOrADimmedOff() {
        assertThat(SettingsFormat.onOff(true)).isEqualTo(ValueText("On"))
        assertThat(SettingsFormat.onOff(false)).isEqualTo(ValueText("Off", dim = true))
    }

    @Test
    fun numbersGetTheirUnits() {
        assertThat(SettingsFormat.number(30, NumberFormat.SECONDS)).isEqualTo("30 s")
        assertThat(SettingsFormat.number(60, NumberFormat.SECONDS)).isEqualTo("1 min")
        assertThat(SettingsFormat.number(90, NumberFormat.SECONDS)).isEqualTo("90 s")
        assertThat(SettingsFormat.number(200, NumberFormat.MILLIS)).isEqualTo("200 ms")
        assertThat(SettingsFormat.number(15, NumberFormat.MINUTES)).isEqualTo("15 min")
        assertThat(SettingsFormat.number(0, NumberFormat.MINUTES)).isEqualTo("Never")
        assertThat(SettingsFormat.number(50, NumberFormat.PERCENT)).isEqualTo("50%")
        assertThat(SettingsFormat.number(-35, NumberFormat.TITLE, "Lowest + 35% shade")).isEqualTo("Lowest + 35% shade")
        assertThat(SettingsFormat.number(7, NumberFormat.TITLE)).isEqualTo("7")
    }

    @Test
    fun choicesShowTheShortLabelAndDimOff() {
        assertThat(SettingsFormat.short("Fit (the whole photo shows)")).isEqualTo("Fit")
        assertThat(SettingsFormat.short("20:00 – 07:00")).isEqualTo("20:00 – 07:00")
        assertThat(SettingsFormat.choice("Never")).isEqualTo(ValueText("Never", dim = true))
        assertThat(SettingsFormat.choice("Sunset to sunrise").dim).isFalse()
    }

    @Test
    fun cyclingWrapsRoundAndStartsOverFromAnUnknownValue() {
        assertThat(SettingsFormat.next(listOf("a", "b", "c"), "b")).isEqualTo("c")
        assertThat(SettingsFormat.next(listOf("a", "b", "c"), "c")).isEqualTo("a")
        assertThat(SettingsFormat.next(listOf("a", "b"), "x")).isEqualTo("a")
        assertThat(SettingsFormat.next(emptyList<String>(), "x")).isNull()
    }

    @Test
    fun secretsAreMaskedAndNeverShown() {
        val token = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.secret"
        val shown = SettingsFormat.text(token, secret = true, builtIn = "", blankLabel = "Not set")
        assertThat(shown).isEqualTo(ValueText(SettingsFormat.MASK, caps = false))
        assertThat(shown.text).doesNotContain("ey")
        // the same mask whatever the length
        assertThat(SettingsFormat.text("ab", secret = true, builtIn = "", blankLabel = "Not set").text).isEqualTo(SettingsFormat.MASK)
        val described = SettingsFormat.textDescription("A long-lived access token.", token, true, "built-in-token", TextEditor.DIALOG)
        assertThat(described).isEqualTo("A long-lived access token.")
    }

    @Test
    fun aBlankValueSaysWhenTheBuiltInOneIsUsed() {
        assertThat(SettingsFormat.text("", secret = true, builtIn = "abc", blankLabel = "Not set")).isEqualTo(ValueText("Built-in"))
        assertThat(SettingsFormat.text("  ", secret = false, builtIn = "https://ha.example.com", blankLabel = "Not set")).isEqualTo(ValueText("Built-in"))
        assertThat(SettingsFormat.textDescription("Home Assistant address", "", false, "https://ha.example.com", TextEditor.DIALOG))
            .isEqualTo("Home Assistant address. Using the built-in https://ha.example.com.")
        // a secret built-in value is never spelled out
        assertThat(SettingsFormat.textDescription("A token. Leave blank to use the built-in one.", "", true, "s3cret", TextEditor.DIALOG))
            .isEqualTo("A token. Leave blank to use the built-in one. Using the built-in one.")
    }

    @Test
    fun aBlankValueWithoutABuiltInOneIsDimmed() {
        assertThat(SettingsFormat.text("", secret = false, builtIn = "", blankLabel = "Default")).isEqualTo(ValueText("Default", dim = true))
        assertThat(SettingsFormat.text("", secret = true, builtIn = "", blankLabel = "Not set")).isEqualTo(ValueText("Not set", dim = true))
    }

    @Test
    fun textIsShownAsTypedAndCut() {
        assertThat(SettingsFormat.text("binary_sensor.quiet", false, "", "Not set")).isEqualTo(ValueText("binary_sensor.quiet", caps = false))
        val long = "https://immich.a-rather-long-domain.example.com"
        val shown = SettingsFormat.text(long, false, "", "Not set").text
        assertThat(shown.length).isEqualTo(SettingsFormat.MAX_TEXT)
        assertThat(shown).endsWith("…")
        // the description then has all of it
        assertThat(SettingsFormat.textDescription("The Immich server.", long, false, "", TextEditor.SIGN_IN)).isEqualTo("The Immich server. $long")
        assertThat(SettingsFormat.textDescription("The Immich server.", "https://short", false, "", TextEditor.SIGN_IN)).isEqualTo("The Immich server.")
    }

    @Test
    fun readOnlyValuesAreSpelledOutInTheDescription() {
        assertThat(SettingsFormat.textDescription("A random ID for this frame", "1234-abcd", false, "", TextEditor.NONE))
            .isEqualTo("A random ID for this frame. 1234-abcd")
    }

    @Test
    fun theSectionLabelCountsRowsOnlyInLongSections() {
        assertThat(SettingsFormat.sectionLabel("Slideshow", 2, 17, 7)).isEqualTo("Slideshow · 3 of 17")
        assertThat(SettingsFormat.sectionLabel("Slideshow", null, 17, 7)).isEqualTo("Slideshow")
        assertThat(SettingsFormat.sectionLabel("Brightness", 2, 7, 7)).isEqualTo("Brightness")
    }
}
