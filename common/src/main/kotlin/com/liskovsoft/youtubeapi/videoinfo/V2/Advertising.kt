package com.liskovsoft.youtubeapi.videoinfo.V2

internal fun Json.Obj.isAdvertisement(): Boolean = fields.any { (name, value) ->
    name in advertisingContainers ||
        (name == "isAd" && (value as? Json.Bool)?.value == true) ||
        (name in advertisingMetadata && value !is Json.Null)
}

private val advertisingMetadata = setOf("adClientParams", "adMetadata", "promotedMetadata")
private val advertisingContainers = setOf(
    "adSlotRenderer", "adPlacementRenderer", "inFeedAdLayoutRenderer", "displayAdRenderer",
    "promotedVideoRenderer", "promotedSparklesWebRenderer", "promotedSparklesTextSearchRenderer",
    "promotedSparklesWebViewRenderer", "searchPyvRenderer", "instreamVideoAdRenderer",
    "adInlinePlaybackRenderer", "carouselAdRenderer", "reelAdRenderer",
)
