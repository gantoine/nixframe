package nl.giejay.android.tv.immich.ambient

import arrow.core.Either
import arrow.core.getOrElse
import nl.giejay.android.tv.immich.api.ApiClient
import nl.giejay.android.tv.immich.api.MAX_SEARCH_PAGE_SIZE
import nl.giejay.android.tv.immich.api.model.Asset
import nl.giejay.android.tv.immich.api.model.AssetResponse
import nl.giejay.android.tv.immich.shared.prefs.ContentType
import nl.giejay.android.tv.immich.shared.util.Utils.pmap
import timber.log.Timber
import java.util.UUID
import kotlin.math.max

/** Where the photo screen's pictures come from ("What to show"). */
enum class PhotoSource(val title: String, val hint: String) {
    ALBUMS("Albums", "Albums you pick"),
    MEMORIES("Memories", "On this day, past years"),
    PEOPLE("People", "Photos of chosen people"),
    ONE_PHOTO("One photo", "Pin a single image"),
    ART("Art gallery", "Curated art and photography");

    companion object {
        fun parse(name: String): PhotoSource = entries.firstOrNull { it.name == name } ?: ALBUMS
    }
}

/** Everything the loader needs, read from preferences by the caller. */
data class SourceSettings(
    val source: PhotoSource,
    val albums: List<String> = emptyList(),
    val people: List<String> = emptyList(),
    val pinnedAsset: String = "",
    val shuffle: Boolean = true,
    val includeVideos: Boolean = false,
    val excludedAlbums: Set<String> = emptySet()
)

/** The first batch of photos, and how to fetch more (null when that was all). */
class SourceBatch(val assets: List<Asset>, val more: (suspend () -> Either<String, AssetResponse>)?)

// internal so app/src/test can call it directly
internal fun <T> Either<String, T>.getOrElseLogged(logContext: String, default: T): T =
    this.onLeft { error -> Timber.w("Failed to load assets for %s: %s", logContext, error) }
        .getOrElse { default }

internal fun filterExcludedAssets(assets: List<Asset>, excludedAssetIds: Set<String>): List<Asset> =
    assets.filter { asset -> asset.tags?.none { it.name == "exclude_immich_tv" } ?: true }
        .filterNot { excludedAssetIds.contains(it.id) }

/**
 * Loads the Immich photos for a [PhotoSource] (all but [PhotoSource.ART], which isn't Immich).
 * Returns Left with a message the photo screen can show when there is nothing to show.
 */
class PhotoSourceLoader(private val apiClient: ApiClient, private val settings: SourceSettings) {

    private var excludedAssetIds: Set<String> = emptySet()
    private val filteredAssetLoader = FilteredAssetLoader(filterAssets = { filterExcludedAssets(it, excludedAssetIds) })
    private val loadedAssetIds = mutableSetOf<String>()
    private val contentType get() = if (settings.includeVideos) ContentType.ALL else ContentType.IMAGE

    suspend fun load(): Either<String, SourceBatch> {
        if (settings.excludedAlbums.isNotEmpty()) {
            excludedAssetIds = apiClient.listAssetsFromAlbum(settings.excludedAlbums.toList(), pageCount = MAX_SEARCH_PAGE_SIZE)
                .getOrElseLogged("excluded albums ${settings.excludedAlbums}", AssetResponse(emptyList(), false))
                .assets.map { it.id }.toSet()
        }
        return when (settings.source) {
            PhotoSource.ALBUMS ->
                if (settings.albums.isEmpty()) Either.Left(NO_ALBUMS) else fromAlbumsOrPeople(settings.albums, emptyList())
            PhotoSource.PEOPLE ->
                if (settings.people.isEmpty()) Either.Left(NO_PEOPLE) else fromAlbumsOrPeople(emptyList(), settings.people)
            PhotoSource.MEMORIES -> memories()
            PhotoSource.ONE_PHOTO -> pinned()
            PhotoSource.ART -> Either.Left("The art gallery isn't an Immich source")
        }
    }

    private suspend fun memories(): Either<String, SourceBatch> {
        val memories = apiClient.getMemories().getOrElse { return Either.Left(COULD_NOT_LOAD) }
        val assets = filterExcludedAssets(memories.flatMap { it.assets }, excludedAssetIds)
            .filter { settings.includeVideos || it.type.equals("IMAGE", ignoreCase = true) }
            .distinctBy { it.id }
        if (assets.isEmpty()) return Either.Left(NO_MEMORIES)
        return Either.Right(SourceBatch(if (settings.shuffle) assets.shuffled() else assets, null))
    }

    private suspend fun pinned(): Either<String, SourceBatch> {
        if (settings.pinnedAsset.isBlank()) return Either.Left(NO_PIN)
        val asset = apiClient.getAsset(settings.pinnedAsset).getOrElse { return Either.Left(COULD_NOT_LOAD) }
        return Either.Right(SourceBatch(listOf(asset), null))
    }

    private suspend fun fromAlbumsOrPeople(albums: List<String>, people: List<String>): Either<String, SourceBatch> {
        return try {
            val response = filteredAssetLoader.load(
                fetch = {
                    if (!settings.shuffle) return@load nextFrom(albums, people, random = false)
                    nextFrom(albums, people, random = true).fold(
                        {
                            Timber.w("Could not load random assets for albums $albums, falling back to all album assets")
                            nextFrom(albums, people, random = false)
                        },
                        { Either.Right(it) }
                    )
                },
                retry = {
                    // The random search skips assets only shared with this user (Immich feature
                    // request), so an empty result falls back to listing the albums in full.
                    Timber.w("Random asset search was empty for albums $albums, using the full album listing")
                    nextFrom(albums, people, random = false)
                }
            ).getOrElse { AssetResponse(emptyList(), false) }
            if (response.assets.isEmpty()) return Either.Left(NOTHING_FOUND)
            Either.Right(
                SourceBatch(
                    if (settings.shuffle) response.assets.shuffled() else response.assets,
                    if (response.canLoadMore) suspend { filteredAssetLoader.load({ nextFrom(albums, people, random = false) }) } else null
                )
            )
        } catch (e: Exception) {
            Timber.e(e, "Could not fetch assets from Immich")
            Either.Left(COULD_NOT_LOAD)
        }
    }

    private suspend fun nextFrom(albums: List<String>, people: List<String>, random: Boolean): Either<String, AssetResponse> {
        val response = if (random) {
            val pageCount = 50 / max(albums.size, people.size).coerceAtLeast(1)
            val responses = if (albums.isNotEmpty()) {
                albums.pmap { albumId ->
                    apiClient.listAssets(page = 1, pageCount = pageCount, random = true, contentType = contentType, albumIds = listOf(albumId))
                }
            } else {
                people.pmap { personId ->
                    apiClient.listAssets(page = 1, pageCount = pageCount, random = true, contentType = contentType, personIds = listOf(UUID.fromString(personId)))
                }
            }
            responses.firstOrNull { it.isLeft() }?.let { return it }
            Either.Right(AssetResponse(responses.flatMap { it.getOrElse { AssetResponse(emptyList(), true) }.assets }.shuffled(), true))
        } else if (albums.isNotEmpty()) {
            apiClient.listAssetsFromAlbum(albums, contentType, pageCount = MAX_SEARCH_PAGE_SIZE)
                .map { AssetResponse(ordered(it.assets), it.canLoadMore) }
        } else {
            apiClient.listAssets(
                page = 1,
                pageCount = MAX_SEARCH_PAGE_SIZE,
                random = false,
                contentType = contentType,
                personIds = people.map { UUID.fromString(it) }.toMutableList()
            ).map { AssetResponse(ordered(it.assets), it.canLoadMore) }
        }
        return response.map { it.copy(assets = it.assets.filter { asset -> loadedAssetIds.add(asset.id) }) }
    }

    /** Shuffled, or oldest first when shuffle is off. */
    private fun ordered(assets: List<Asset>): List<Asset> =
        if (settings.shuffle) assets.shuffled()
        else assets.sortedBy { (it.exifInfo?.dateTimeOriginal ?: it.fileCreatedAt ?: it.fileModifiedAt)?.time ?: Long.MAX_VALUE }

    companion object {
        const val NO_ALBUMS = "Pick albums in What to show — press Back"
        const val NO_PEOPLE = "Pick people in What to show — press Back"
        const val NO_PIN = "No photo pinned yet — open What to show while the photo is on screen"
        const val NO_MEMORIES = "No memories for today"
        const val NOTHING_FOUND = "No photos found for this source"
        const val COULD_NOT_LOAD = "Couldn't load photos from Immich"
    }
}
