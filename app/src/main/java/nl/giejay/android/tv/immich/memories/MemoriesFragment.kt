package nl.giejay.android.tv.immich.memories

import androidx.navigation.fragment.findNavController
import androidx.fragment.app.activityViewModels
import arrow.core.Either
import nl.giejay.android.tv.immich.album.AlbumDetailsFragmentDirections
import nl.giejay.android.tv.immich.api.ApiClient
import nl.giejay.android.tv.immich.api.model.Memory
import nl.giejay.android.tv.immich.api.util.ApiUtil
import nl.giejay.android.tv.immich.card.Card
import nl.giejay.android.tv.immich.shared.fragment.VerticalCardGridFragment
import nl.giejay.android.tv.immich.timeline.MemoryPresenter
import nl.giejay.android.tv.immich.timeline.toSliderConfiguration
import nl.giejay.mediaslider.viewmodel.MediaSliderViewModel
import java.text.DateFormat
import java.util.Date

/**
 * Memories ("on this day" in past years, from GET /memories) as a grid: each card is one memory,
 * titled with its date and "N years ago". Opening one plays its photos/videos in the slider,
 * exactly like the memories row on the Timeline.
 */
class MemoriesFragment : VerticalCardGridFragment<Memory>() {
    private val sliderViewModel: MediaSliderViewModel by activityViewModels()
    private val memoriesById = mutableMapOf<String, Memory>()

    override suspend fun loadItems(apiClient: ApiClient, page: Int, pageCount: Int): Either<String, List<Memory>> {
        if (page != startPage) return Either.Right(emptyList())
        val now = Date()
        // Skip memories queued for upcoming days (showAt in the future), like Immich's web UI.
        return apiClient.listRecentMemories().map { memories ->
            memories.filter { it.assets.isNotEmpty() && (it.showAt == null || !it.showAt.after(now)) }
        }
    }

    override fun allPagesLoaded(items: List<Memory>): Boolean = true

    // Most recently surfaced first (like Immich's web UI), then by year.
    override fun sortItems(items: List<Memory>): List<Memory> =
        items.sortedWith(compareByDescending<Memory> { it.showAt ?: it.memoryAt }.thenByDescending { it.memoryAt })

    override fun itemKey(item: Memory): Any = item.id

    override fun createCard(a: Memory): Card {
        memoriesById[a.id] = a
        val cover = a.assets.first()
        return Card(
            DateFormat.getDateInstance(DateFormat.MEDIUM).format(a.memoryAt),
            MemoryPresenter.yearsAgoLabel(requireContext(), a),
            a.id,
            ApiUtil.getThumbnailUrl(cover.id, "thumbnail"),
            ApiUtil.getThumbnailUrl(cover.id, "preview")
        )
    }

    override fun getBackgroundPicture(it: Memory): String? =
        it.assets.firstOrNull()?.let { a -> ApiUtil.getThumbnailUrl(a.id, "preview") }

    override fun onItemSelected(card: Card, indexOf: Int) {
        // no selection mode for memories
    }

    override fun onItemClicked(card: Card) {
        val memory = memoriesById[card.id] ?: return
        sliderViewModel.configuration = memory.toSliderConfiguration()
        findNavController().navigate(AlbumDetailsFragmentDirections.actionToPhotoSlider(timelineView = true))
    }
}
