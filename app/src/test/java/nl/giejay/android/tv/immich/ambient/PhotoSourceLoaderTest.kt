package nl.giejay.android.tv.immich.ambient

import arrow.core.Either
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import nl.giejay.android.tv.immich.api.ApiClient
import nl.giejay.android.tv.immich.api.MAX_SEARCH_PAGE_SIZE
import nl.giejay.android.tv.immich.api.model.Asset
import nl.giejay.android.tv.immich.api.model.AssetResponse
import nl.giejay.android.tv.immich.api.model.Memory
import nl.giejay.android.tv.immich.api.model.MemoryData
import nl.giejay.android.tv.immich.api.model.Tag
import nl.giejay.android.tv.immich.shared.prefs.ContentType
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.isNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.util.Date

@OptIn(ExperimentalCoroutinesApi::class)
class PhotoSourceLoaderTest {

    private fun asset(id: String, type: String = "IMAGE", tags: List<Tag>? = null) = Asset(
        id = id,
        type = type,
        deviceAssetId = null,
        exifInfo = null,
        fileModifiedAt = null,
        albumName = null,
        people = null,
        tags = tags,
        originalPath = null,
        originalFileName = null
    )

    private suspend fun stubRandomSearch(apiClient: ApiClient, result: Either<String, AssetResponse>) {
        whenever(
            apiClient.listAssets(
                page = any(), pageCount = any(), random = any(), order = any(), personIds = any(),
                fromDate = anyOrNull(), endDate = anyOrNull(), contentType = any(), albumIds = any(), city = anyOrNull()
            )
        ).thenReturn(result)
    }

    private fun ids(result: Either<String, SourceBatch>): List<String> =
        result.fold({ error("expected photos, got: $it") }, { batch -> batch.assets.map { it.id } })

    @Test
    fun `albums fall back to the full album listing when the random search is empty`() = runTest {
        val apiClient = mock<ApiClient>()
        stubRandomSearch(apiClient, Either.Right(AssetResponse(emptyList(), true)))
        whenever(apiClient.listAssetsFromAlbum(eq(listOf("shared-album")), eq(ContentType.IMAGE), eq(MAX_SEARCH_PAGE_SIZE)))
            .thenReturn(Either.Right(AssetResponse(listOf(asset("shared-asset")), false)))

        val result = PhotoSourceLoader(apiClient, SourceSettings(PhotoSource.ALBUMS, albums = listOf("shared-album"))).load()

        verify(apiClient, times(1)).listAssets(eq(1), eq(50), eq(true), any(), any(), isNull(), isNull(), eq(ContentType.IMAGE), eq(listOf("shared-album")), isNull())
        assertThat(ids(result)).containsExactly("shared-asset")
    }

    @Test
    fun `albums without a pick explain what to do instead of loading`() = runTest {
        val apiClient = mock<ApiClient>()
        val result = PhotoSourceLoader(apiClient, SourceSettings(PhotoSource.ALBUMS)).load()
        assertThat(result).isEqualTo(Either.Left(PhotoSourceLoader.NO_ALBUMS))
        verify(apiClient, never()).listAssetsFromAlbum(any(), any(), any())
    }

    @Test
    fun `shuffle off lists the album oldest first instead of searching at random`() = runTest {
        val apiClient = mock<ApiClient>()
        val old = asset("old").copy(fileModifiedAt = Date(1_000))
        val new = asset("new").copy(fileModifiedAt = Date(2_000))
        whenever(apiClient.listAssetsFromAlbum(eq(listOf("a")), eq(ContentType.IMAGE), eq(MAX_SEARCH_PAGE_SIZE)))
            .thenReturn(Either.Right(AssetResponse(listOf(new, old), false)))

        val result = PhotoSourceLoader(apiClient, SourceSettings(PhotoSource.ALBUMS, albums = listOf("a"), shuffle = false)).load()

        assertThat(ids(result)).containsExactly("old", "new").inOrder()
        verify(apiClient, never()).listAssets(any(), any(), eq(true), any(), any(), anyOrNull(), anyOrNull(), any(), any(), anyOrNull())
    }

    @Test
    fun `people load photos of the chosen people`() = runTest {
        val apiClient = mock<ApiClient>()
        stubRandomSearch(apiClient, Either.Right(AssetResponse(listOf(asset("face")), true)))
        val person = "8d0f0a4e-0c2a-4c55-9a4c-62f4f5e5b0a1"

        val result = PhotoSourceLoader(apiClient, SourceSettings(PhotoSource.PEOPLE, people = listOf(person))).load()

        assertThat(ids(result)).containsExactly("face")
    }

    @Test
    fun `memories flatten today's memories, skipping videos unless asked`() = runTest {
        val apiClient = mock<ApiClient>()
        val memory = Memory("m", "on_this_day", Date(), Date(), MemoryData(2020),listOf(asset("p1"), asset("v1", type = "VIDEO"), asset("p1")))
        whenever(apiClient.getMemories()).thenReturn(Either.Right(listOf(memory)))

        val result = PhotoSourceLoader(apiClient, SourceSettings(PhotoSource.MEMORIES)).load()

        assertThat(ids(result)).containsExactly("p1")
    }

    @Test
    fun `no memories today says so`() = runTest {
        val apiClient = mock<ApiClient>()
        whenever(apiClient.getMemories()).thenReturn(Either.Right(emptyList()))
        val result = PhotoSourceLoader(apiClient, SourceSettings(PhotoSource.MEMORIES)).load()
        assertThat(result).isEqualTo(Either.Left(PhotoSourceLoader.NO_MEMORIES))
    }

    @Test
    fun `one photo loads the pinned asset`() = runTest {
        val apiClient = mock<ApiClient>()
        whenever(apiClient.getAsset("pinned")).thenReturn(Either.Right(asset("pinned")))
        val result = PhotoSourceLoader(apiClient, SourceSettings(PhotoSource.ONE_PHOTO, pinnedAsset = "pinned")).load()
        assertThat(ids(result)).containsExactly("pinned")
    }

    @Test
    fun `one photo without a pin explains how to pin`() = runTest {
        val result = PhotoSourceLoader(mock(), SourceSettings(PhotoSource.ONE_PHOTO)).load()
        assertThat(result).isEqualTo(Either.Left(PhotoSourceLoader.NO_PIN))
    }

    @Test
    fun `filters out assets from excluded albums`() = runTest {
        val apiClient = mock<ApiClient>()
        whenever(apiClient.listAssetsFromAlbum(eq(listOf("excluded-album")), any(), any()))
            .thenReturn(Either.Right(AssetResponse(listOf(asset("excluded-asset")), false)))
        stubRandomSearch(apiClient, Either.Right(AssetResponse(listOf(asset("kept"), asset("excluded-asset")), true)))

        val result = PhotoSourceLoader(apiClient, SourceSettings(PhotoSource.ALBUMS, albums = listOf("a"), excludedAlbums = setOf("excluded-album"))).load()

        // Immich rejects search page sizes above 1000; a larger value silently disabled album exclusion
        val pageCount = argumentCaptor<Int>()
        verify(apiClient).listAssetsFromAlbum(eq(listOf("excluded-album")), any(), pageCount.capture())
        assertThat(pageCount.firstValue).isAtMost(MAX_SEARCH_PAGE_SIZE)
        assertThat(ids(result)).containsExactly("kept")
    }

    @Test
    fun `filters out assets with the exclude tag`() = runTest {
        val apiClient = mock<ApiClient>()
        val tagged = asset("tagged", tags = listOf(Tag(null, Date(), "exclude_immich_tv", "")))
        stubRandomSearch(apiClient, Either.Right(AssetResponse(listOf(asset("normal"), tagged), true)))

        val result = PhotoSourceLoader(apiClient, SourceSettings(PhotoSource.ALBUMS, albums = listOf("a"))).load()

        assertThat(ids(result)).containsExactly("normal")
    }
}
