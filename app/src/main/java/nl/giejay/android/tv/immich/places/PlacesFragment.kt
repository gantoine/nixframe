package nl.giejay.android.tv.immich.places

import androidx.navigation.fragment.findNavController
import arrow.core.Either
import nl.giejay.android.tv.immich.api.ApiClient
import nl.giejay.android.tv.immich.api.model.Asset
import nl.giejay.android.tv.immich.api.util.ApiUtil
import nl.giejay.android.tv.immich.card.Card
import nl.giejay.android.tv.immich.home.HomeFragmentDirections
import nl.giejay.android.tv.immich.shared.fragment.VerticalCardGridFragment

/**
 * Places: one card per city in the library (Immich's GET /search/cities), each showing a
 * representative photo. Opening a place lists every asset taken in that city.
 */
class PlacesFragment : VerticalCardGridFragment<Asset>() {

    override suspend fun loadItems(apiClient: ApiClient, page: Int, pageCount: Int): Either<String, List<Asset>> {
        // The endpoint returns every city at once; there's nothing to paginate.
        return if (page == startPage) apiClient.listPlaces() else Either.Right(emptyList())
    }

    override fun allPagesLoaded(items: List<Asset>): Boolean = true

    // Already sorted by city name in ApiClient.listPlaces.
    override fun sortItems(items: List<Asset>): List<Asset> = items

    override fun itemKey(item: Asset): Any = city(item)

    override fun createCard(a: Asset): Card = Card(
        city(a),
        a.exifInfo?.country ?: "",
        city(a),
        ApiUtil.getThumbnailUrl(a.id, "thumbnail"),
        ApiUtil.getThumbnailUrl(a.id, "preview")
    )

    override fun getBackgroundPicture(it: Asset): String? = ApiUtil.getThumbnailUrl(it.id, "preview")

    override fun onItemSelected(card: Card, indexOf: Int) {
        // no selection mode for places
    }

    override fun onItemClicked(card: Card) {
        findNavController().navigate(HomeFragmentDirections.actionHomeFragmentToPlaceAssetsFragment(card.id))
    }

    private fun city(asset: Asset): String = asset.exifInfo?.city ?: ""
}
