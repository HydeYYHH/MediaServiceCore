package com.liskovsoft.youtubeapi.videoinfo.V2

import java.net.URLDecoder
import java.util.Base64

enum class SearchFilterType { ALL, VIDEO, CHANNEL, PLAYLIST, MOVIE, SHORTS }
enum class SearchFilterDate { ANYTIME, TODAY, THIS_WEEK, THIS_MONTH, THIS_YEAR }
enum class SearchFilterDuration { ANY, UNDER_THREE_MINUTES, THREE_TO_TWENTY_MINUTES, OVER_TWENTY_MINUTES }
enum class SearchFilterOrder { RELEVANCE, POPULARITY }
enum class SearchFilterFeature { LIVE, HD, FOUR_K, SUBTITLES, CREATIVE_COMMONS, THREE_D, THREE_SIXTY, VR180, LOCATION, HDR, PURCHASED }
enum class SearchFilterDimension { TYPE, UPLOAD_DATE, DURATION, FEATURES, ORDER }

/** The complete selection encoded by an issued WEB search endpoint; it never constructs params. */
data class SearchFilterState @JvmOverloads constructor(
    val type: SearchFilterType = SearchFilterType.ALL,
    val uploaded: SearchFilterDate = SearchFilterDate.ANYTIME,
    val duration: SearchFilterDuration = SearchFilterDuration.ANY,
    val features: Set<SearchFilterFeature> = emptySet(),
    val order: SearchFilterOrder = SearchFilterOrder.RELEVANCE,
) {
    fun differences(other: SearchFilterState): Set<SearchFilterDimension> = buildSet {
        if (type != other.type) add(SearchFilterDimension.TYPE)
        if (uploaded != other.uploaded) add(SearchFilterDimension.UPLOAD_DATE)
        if (duration != other.duration) add(SearchFilterDimension.DURATION)
        if (features != other.features) add(SearchFilterDimension.FEATURES)
        if (order != other.order) add(SearchFilterDimension.ORDER)
    }
}

/** Bounded, closed wire projection. Unknown or conflicting semantics remain opaque capabilities. */
internal fun searchFilterState(params: String?): SearchFilterState? = runCatching {
    if (params == null) return@runCatching SearchFilterState()
    require(params.isNotBlank() && params.length <= 8192)
    val decoded = URLDecoder.decode(params.replace("+", "%2B"), "UTF-8")
    val bytes = Base64.getUrlDecoder().decode(decoded.replace('+', '-').replace('/', '_'))
    val root = SearchFilterWire(bytes).fields()
    require(root.keys.all { it in setOf(1, 2) })
    val order = when (root[1]?.number ?: 0L) {
        0L -> SearchFilterOrder.RELEVANCE
        3L -> SearchFilterOrder.POPULARITY
        else -> error("unknown search order")
    }
    require(root[1]?.bytes == null)
    val filters = root[2]?.let { require(it.number == null); SearchFilterWire(requireNotNull(it.bytes)).fields() }.orEmpty()
    require(filters.keys.all { it in setOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 14, 15, 23, 25, 26) })
    require(filters.values.all { it.number != null })
    val type = when (filters[2]?.number ?: 0L) {
        0L -> SearchFilterType.ALL; 1L -> SearchFilterType.VIDEO; 2L -> SearchFilterType.CHANNEL
        3L -> SearchFilterType.PLAYLIST; 4L -> SearchFilterType.MOVIE; 9L -> SearchFilterType.SHORTS
        else -> error("unknown search type")
    }
    val date = when (filters[1]?.number ?: 0L) {
        0L -> SearchFilterDate.ANYTIME; 2L -> SearchFilterDate.TODAY; 3L -> SearchFilterDate.THIS_WEEK
        4L -> SearchFilterDate.THIS_MONTH; 5L -> SearchFilterDate.THIS_YEAR
        else -> error("unknown search date")
    }
    val duration = when (filters[3]?.number ?: 0L) {
        0L -> SearchFilterDuration.ANY; 2L -> SearchFilterDuration.OVER_TWENTY_MINUTES
        4L -> SearchFilterDuration.UNDER_THREE_MINUTES; 5L -> SearchFilterDuration.THREE_TO_TWENTY_MINUTES
        else -> error("unknown search duration")
    }
    val features = mapOf(4 to SearchFilterFeature.HD, 5 to SearchFilterFeature.SUBTITLES, 6 to SearchFilterFeature.CREATIVE_COMMONS,
        7 to SearchFilterFeature.THREE_D, 8 to SearchFilterFeature.LIVE, 9 to SearchFilterFeature.PURCHASED,
        14 to SearchFilterFeature.FOUR_K, 15 to SearchFilterFeature.THREE_SIXTY, 23 to SearchFilterFeature.LOCATION,
        25 to SearchFilterFeature.HDR, 26 to SearchFilterFeature.VR180).mapNotNull { (field, feature) ->
        val value = filters[field]?.number ?: return@mapNotNull null
        require(value in 0L..1L)
        feature.takeIf { value == 1L }
    }.toSet()
    SearchFilterState(type, date, duration, features, order)
}.getOrNull()

private class SearchFilterWire(private val bytes: ByteArray) {
    data class Value(val number: Long? = null, val bytes: ByteArray? = null)
    private var offset = 0
    private fun varint(): Long {
        var result = 0L
        for (shift in 0..63 step 7) {
            require(offset < bytes.size)
            val value = bytes[offset++].toInt() and 255
            require(shift != 63 || value <= 1)
            result = result or ((value and 127).toLong() shl shift)
            if (value and 128 == 0) return result.also { require(it >= 0) }
        }
        error("invalid varint")
    }
    fun fields(): Map<Int, Value> {
        require(bytes.size <= 6144)
        val result = LinkedHashMap<Int, Value>()
        while (offset < bytes.size) {
            require(result.size < 32)
            val tag = varint()
            val field = (tag ushr 3).also { require(it in 1L..536_870_911L) }.toInt()
            require(field !in result)
            result[field] = when (tag and 7L) {
                0L -> Value(number = varint())
                2L -> {
                    val size = varint().also { require(it <= bytes.size - offset) }.toInt()
                    Value(bytes = bytes.copyOfRange(offset, offset + size)).also { offset += size }
                }
                else -> error("unsupported search wire type")
            }
        }
        return result
    }
}
