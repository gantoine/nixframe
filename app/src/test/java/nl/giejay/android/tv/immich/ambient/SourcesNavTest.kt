package nl.giejay.android.tv.immich.ambient

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SourcesNavTest {
    // 5 albums (rows: 0 1 / 2 3 / 4) above 4 settings (5 6 / 7 8)
    private val grid = PanelGrid(picks = 5, settings = 4)

    @Test
    fun downWalksTheRowsThenIntoTheSettings() {
        assertThat(grid.down(0)).isEqualTo(2)
        assertThat(grid.down(2)).isEqualTo(4)
        assertThat(grid.down(3)).isEqualTo(4) // the last row has one album: step onto it
        assertThat(grid.down(4)).isEqualTo(5) // into the settings, same column
        assertThat(grid.down(5)).isEqualTo(7)
        assertThat(grid.down(7)).isEqualTo(7) // bottom
    }

    @Test
    fun upWalksBackToTheLastAlbumRow() {
        assertThat(grid.up(7)).isEqualTo(5)
        assertThat(grid.up(5)).isEqualTo(4)
        assertThat(grid.up(6)).isEqualTo(4) // no album in the right column of the last row
        assertThat(grid.up(2)).isEqualTo(0)
        assertThat(grid.up(0)).isEqualTo(0)
    }

    @Test
    fun leftFromTheLeftColumnLeavesThePanel() {
        assertThat(grid.left(1)).isEqualTo(0)
        assertThat(grid.left(0)).isNull()
        assertThat(grid.left(6)).isEqualTo(5)
        assertThat(grid.left(5)).isNull()
        assertThat(grid.right(0)).isEqualTo(1)
        assertThat(grid.right(4)).isEqualTo(4) // nothing to its right
        assertThat(grid.right(5)).isEqualTo(6)
    }

    @Test
    fun aPanelWithoutPicksIsJustTheSettings() {
        val settingsOnly = PanelGrid(picks = 0, settings = 4)
        assertThat(settingsOnly.first()).isEqualTo(0)
        assertThat(settingsOnly.up(0)).isEqualTo(0)
        assertThat(settingsOnly.down(1)).isEqualTo(3)
        assertThat(PanelGrid(0, 0).first()).isNull()
    }

    @Test
    fun scrollingKeepsTheFocusedRowVisible() {
        assertThat(PanelGrid.scrollRow(firstRow = 0, focusRow = 3, visibleRows = 6, totalRows = 20)).isEqualTo(0)
        assertThat(PanelGrid.scrollRow(firstRow = 0, focusRow = 6, visibleRows = 6, totalRows = 20)).isEqualTo(1)
        assertThat(PanelGrid.scrollRow(firstRow = 5, focusRow = 2, visibleRows = 6, totalRows = 20)).isEqualTo(2)
        assertThat(PanelGrid.scrollRow(firstRow = 10, focusRow = 19, visibleRows = 6, totalRows = 20)).isEqualTo(14)
        assertThat(PanelGrid.scrollRow(firstRow = 3, focusRow = 1, visibleRows = 6, totalRows = 4)).isEqualTo(0)
    }

    @Test
    fun intervals() {
        assertThat(IntervalChoices.next(30)).isEqualTo(60)
        assertThat(IntervalChoices.next(600)).isEqualTo(10)
        assertThat(IntervalChoices.next(6)).isEqualTo(10) // an old value off the list
        assertThat(IntervalChoices.label(30)).isEqualTo("30 s")
        assertThat(IntervalChoices.label(180)).isEqualTo("3 min")
    }
}
