package nl.giejay.android.tv.immich.places

import android.os.Bundle
import arrow.core.Either
import nl.giejay.android.tv.immich.api.ApiClient
import nl.giejay.android.tv.immich.api.model.Asset
import nl.giejay.android.tv.immich.assets.GenericAssetFragment

/** All assets taken in one city (POST /search/metadata with `city`), newest first, paginated. */
class PlaceAssetsFragment : GenericAssetFragment() {
    private lateinit var city: String

    override fun onCreate(savedInstanceState: Bundle?) {
        city = PlaceAssetsFragmentArgs.fromBundle(requireArguments()).city
        super.onCreate(savedInstanceState)
    }

    override suspend fun loadItems(apiClient: ApiClient, page: Int, pageCount: Int): Either<String, List<Asset>> {
        return apiClient.listAssets(
            page,
            pageCount,
            order = "desc",
            contentType = currentFilter,
            city = city
        ).map { it.assets }
    }

    override fun setTitle(response: List<Asset>) {
        title = city
    }
}
