package nl.giejay.android.tv.immich.api.service

import nl.giejay.android.tv.immich.api.model.Album
import nl.giejay.android.tv.immich.api.model.Asset
import nl.giejay.android.tv.immich.api.model.Memory
import nl.giejay.android.tv.immich.api.model.PeopleResponse
import nl.giejay.android.tv.immich.api.model.SearchRequest
import nl.giejay.android.tv.immich.api.model.SearchResponse
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

interface ApiService {
    @POST("search/metadata")
    suspend fun listAssets(@Body searchRequest: SearchRequest): Response<SearchResponse>

    @POST("search/random")
    suspend fun randomAssets(@Body searchRequest: SearchRequest): Response<List<Asset>>

    @GET("albums")
    suspend fun listAlbums(@Query("assetId") assetId: String? = null): Response<List<Album>>

    @GET("albums/{id}")
    suspend fun getAlbum(@Path("id") id: String): Response<Album>

    @GET("assets/{id}")
    suspend fun getAsset(@Path("id") id: String): Response<Asset>

    @GET("people")
    suspend fun listPeople(): Response<PeopleResponse>

    /** [forDate] = memories shown on that day; null lists all memories (with [order]/[size]). */
    @GET("memories")
    suspend fun getMemories(
        @Query("for") forDate: String?,
        @Query("order") order: String? = null,
        @Query("size") size: Int? = null
    ): Response<List<Memory>>

}
