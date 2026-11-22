package nl.giejay.android.tv.immich.api

import arrow.core.Either
import arrow.core.None
import arrow.core.Option
import arrow.core.flatMap
import arrow.core.getOrElse
import nl.giejay.android.tv.immich.api.model.Album
import nl.giejay.android.tv.immich.api.model.Asset
import nl.giejay.android.tv.immich.api.model.AssetResponse
import nl.giejay.android.tv.immich.api.model.Memory
import nl.giejay.android.tv.immich.api.model.Person
import nl.giejay.android.tv.immich.api.model.SearchRequest
import nl.giejay.android.tv.immich.api.model.SearchResponse
import nl.giejay.android.tv.immich.api.service.ApiService
import nl.giejay.android.tv.immich.api.util.ApiUtil.executeAPICall
import nl.giejay.android.tv.immich.shared.prefs.API_KEY
import nl.giejay.android.tv.immich.shared.prefs.ContentType
import nl.giejay.android.tv.immich.shared.prefs.DEBUG_MODE
import nl.giejay.android.tv.immich.shared.prefs.DISABLE_SSL_VERIFICATION
import nl.giejay.android.tv.immich.shared.prefs.EXCLUDE_ASSETS_IN_ALBUM
import nl.giejay.android.tv.immich.shared.prefs.PreferenceManager
import nl.giejay.android.tv.immich.shared.util.Utils.pmap
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

data class ApiClientConfig(
    val hostName: String,
    val apiKey: String,
    val disableSslVerification: Boolean,
    val debugMode: Boolean
) {
    companion object {
        fun fromPrefs(): ApiClientConfig = ApiClientConfig(
            PreferenceManager.hostName,
            PreferenceManager.get(API_KEY),
            PreferenceManager.get(DISABLE_SSL_VERIFICATION),
            PreferenceManager.get(DEBUG_MODE)
        )
    }
}

// Immich rejects /search/metadata and /search/random requests with a size above 1000 (400 Bad Request)
const val MAX_SEARCH_PAGE_SIZE = 1000

// internal so app/src/test can call it directly without instantiating ApiClient/Retrofit
internal fun buildListAssetsSearchRequest(
    page: Int,
    pageCount: Int,
    albumIds: List<String>,
    order: String,
    type: String?,
    personIds: List<UUID>,
    endDate: String?,
    fromDate: String?,
    city: String? = null
): SearchRequest = SearchRequest(
    page = page,
    size = pageCount,
    albumIds = albumIds,
    order = order,
    type = type,
    personIds = personIds,
    takenBefore = endDate,
    takenAfter = fromDate,
    // "timeline" reproduces pre-v3 default for normal browsing; album-scoped search
    // omits it so archived-in-album assets keep showing (VIS-02)
    visibility = if (albumIds.isEmpty()) "timeline" else null,
    city = city
)

internal fun SearchResponse.toAssetResponse(): AssetResponse =
    AssetResponse(assets.items, assets.nextPage != null)

internal fun List<Asset>.toRandomAssetResponse(): AssetResponse =
    AssetResponse(this, true)

class ApiClient(private val config: ApiClientConfig) {
    companion object ApiClient {
        private var apiClient: nl.giejay.android.tv.immich.api.ApiClient? = null
        fun getClient(config: ApiClientConfig): nl.giejay.android.tv.immich.api.ApiClient {
            if (config != apiClient?.config) {
                apiClient = ApiClient(config)
            }
            return apiClient!!
        }

        // ISO_DATE_TIME on a zone-less LocalDateTime omits the offset entirely (e.g. "2026-07-04T14:30:00"),
        // but Immich v3's takenBefore/takenAfter schema requires a mandatory trailing Z/offset
        // (^...T...(?:Z|[+-]HH:mm)$) - v2 tolerated the offset-less form, v3's stricter validation
        // rejects it with a 400. Attach the device's zone before formatting so the offset is always present.
        val dateTimeFormatter: DateTimeFormatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME;
    }

    private val retrofit = Retrofit.Builder()
        .client(ApiClientFactory.getClient(config.disableSslVerification, config.apiKey, config.debugMode))
        .addConverterFactory(GsonConverterFactory.create())
        .baseUrl("${config.hostName}/api/")
        .build()

    private val service: ApiService = retrofit.create(ApiService::class.java)

    private suspend fun search(searchRequest: SearchRequest): Either<String, SearchResponse> {
        return executeAPICall(200) { service.listAssets(searchRequest) }
    }

    suspend fun listAlbums(assetId: Option<String> = None): Either<String, List<Album>> {
        return executeAPICall(200) { service.listAlbums(assetId.getOrNull()) }
    }

    suspend fun getAlbum(albumId: String): Either<String, Album> {
        return executeAPICall(200) { service.getAlbum(albumId) }
    }

    /**
     * People in the library, in the server's order. Unnamed faces are dropped unless
     * [includeUnnamed] (the People page shows them like Immich's web UI; pickers keep names only).
     */
    suspend fun listPeople(includeUnnamed: Boolean = false): Either<String, List<Person>> {
        return executeAPICall(200) { service.listPeople() }.map { response ->
            response.people.filter { includeUnnamed || !it.name.isNullOrBlank() }
        }
    }

    suspend fun listAssetsFromAlbum(albumIds: List<String>,
                                    contentType: ContentType = ContentType.ALL,
                                    pageCount: Int = 100): Either<String, AssetResponse> {
        val results = albumIds.pmap { albumId ->
            val album = executeAPICall(200) { service.getAlbum(albumId) }
                .getOrElse { return@pmap Either.Left(it) }

            val total = album.assetCount
            val size = pageCount.coerceAtMost(MAX_SEARCH_PAGE_SIZE)
            val numPages = (total + size - 1) / size
            val pages = (1..numPages).toList()

            val assetsResults = pages.pmap { page ->
                search(SearchRequest(
                    page = page,
                    size = size,
                    albumIds = listOf(albumId),
                    type = if (contentType == ContentType.ALL) null else contentType.toString()
                ))
            }

            val albumAssets = mutableListOf<Asset>()
            for (res in assetsResults) {
                val resp = res.getOrElse { return@pmap Either.Left(it) }
                albumAssets.addAll(resp.assets.items)
            }
            Either.Right(albumAssets.toList())
        }

        val allAssets = mutableListOf<Asset>()
        for (res in results) {
            val assets = res.getOrElse { return Either.Left(it) }
            allAssets.addAll(assets)
        }
        return Either.Right(AssetResponse(allAssets.filter(excludeByTag()), false))
    }

    suspend fun listAssets(page: Int,
                           pageCount: Int,
                           random: Boolean = false,
                           order: String = "desc",
                           personIds: List<UUID> = emptyList(),
                           fromDate: LocalDateTime? = null,
                           endDate: LocalDateTime? = null,
                           contentType: ContentType,
                           albumIds: List<String> = emptyList(),
                           city: String? = null): Either<String, AssetResponse> {
        val searchRequest = buildListAssetsSearchRequest(
            page = page,
            pageCount = pageCount,
            albumIds = albumIds,
            order = order,
            type = if (contentType == ContentType.ALL) null else contentType.toString(),
            personIds = personIds,
            endDate = endDate?.atZone(ZoneId.systemDefault())?.format(dateTimeFormatter),
            fromDate = fromDate?.atZone(ZoneId.systemDefault())?.format(dateTimeFormatter),
            city = city
        )
        val assetsResult = if (random) {
            // RandomSearchDto (POST /search/random) has no `page`/`order` properties at all -
            // MetadataSearchDto is the only search DTO that supports pagination/ordering. Immich v3's
            // stricter request validation rejects unknown properties with a 400, so they must be
            // stripped here rather than sent as on the metadata-search path below.
            executeAPICall(200) { service.randomAssets(searchRequest.copy(page = null, order = null)) }
                .map { it.toRandomAssetResponse() }
        } else {
            search(searchRequest).map { it.toAssetResponse() }
        }
        return assetsResult
    }

    private fun excludeByTag() = { asset: Asset ->
        asset.tags?.none { t -> t.name == "exclude_immich_tv" } ?: true
    }

    suspend fun getAsset(id: String): Either<String, Asset> {
        return executeAPICall(200) { service.getAsset(id) }
    }

    suspend fun getMemories(): Either<String, List<Memory>> {
        val date = LocalDate.now().toString()
        return executeAPICall(200) { service.getMemories(date) }.fold(
            { error ->
                if (error.contains("(400)")) {
                    val isoDate = OffsetDateTime.now().format(dateTimeFormatter)
                    executeAPICall(200) { service.getMemories(isoDate) }
                } else {
                    Either.Left(error)
                }
            },
            { Either.Right(it) }
        )
    }
}
