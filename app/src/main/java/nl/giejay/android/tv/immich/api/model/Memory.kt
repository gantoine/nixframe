package nl.giejay.android.tv.immich.api.model

import java.util.Date

data class MemoryData(
    val year: Int
)

/**
 * Mirror of Immich's MemoryResponseDto (GET /memories). `assets` reuses the same
 * AssetResponseDto shape already modeled by [Asset] elsewhere in the app.
 */
data class Memory(
    val id: String,
    val type: String,
    val memoryAt: Date,
    /** When the memory surfaced (start of its "on this day"); null on older servers. */
    val showAt: Date?,
    val data: MemoryData,
    val assets: List<Asset>
)
