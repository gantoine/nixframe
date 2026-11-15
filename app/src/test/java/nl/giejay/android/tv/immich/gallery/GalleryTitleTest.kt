package nl.giejay.android.tv.immich.gallery

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class GalleryTitleTest {
    private fun photo(description: String? = null, alt: String? = null, location: String? = null) = UnsplashPhoto(
        id = "x", width = 1, height = 1, description = description, altDescription = alt,
        urls = UnsplashUrls("https://images.unsplash.com/x"), user = null,
        location = location?.let { UnsplashLocation(it) }
    )

    @Test
    fun `a short description is used as the title`() {
        assertThat(GalleryRepository.titleFor(photo("Morning fog over the lake", "a lake"))).isEqualTo("Morning fog over the lake")
    }

    @Test
    fun `long or hashtag descriptions fall back to the capitalised alt text`() {
        val long = "A".repeat(120)
        assertThat(GalleryRepository.titleFor(photo(long, "a lake surrounded by mountains"))).isEqualTo("A lake surrounded by mountains")
        assertThat(GalleryRepository.titleFor(photo("sunset #travel #nature", "orange sky"))).isEqualTo("Orange sky")
    }

    @Test
    fun `only the first line of a description is considered`() {
        assertThat(GalleryRepository.titleFor(photo("Alpine meadow\nShot on film, 2019"))).isEqualTo("Alpine meadow")
    }

    @Test
    fun `falls back to location, then Untitled`() {
        assertThat(GalleryRepository.titleFor(photo(location = "Banff, Canada"))).isEqualTo("Banff, Canada")
        assertThat(GalleryRepository.titleFor(photo())).isEqualTo("Untitled")
    }

    @Test
    fun `overlong alt text is truncated with an ellipsis`() {
        val title = GalleryRepository.titleFor(photo(alt = "a ".repeat(60) + "end"))
        assertThat(title.length).isAtMost(70)
        assertThat(title).endsWith("…")
    }
}
