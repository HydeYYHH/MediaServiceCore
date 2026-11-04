package com.liskovsoft.youtubeapi.videoinfo.V2

internal fun mediaWaitMs(root: Json.Obj): Long {
    val slots = root.arr("adSlots").orEmpty().mapNotNull { (it as? Json.Obj)?.obj("adSlotRenderer") }
        .filter { it.obj("adSlotMetadata")?.text("triggerEvent") == "SLOT_TRIGGER_EVENT_BEFORE_CONTENT" }
    val content = if (slots.isNotEmpty()) slots.map { it.fields["fulfillmentContent"] } else
        root.arr("adPlacements").orEmpty().mapNotNull { (it as? Json.Obj)?.obj("adPlacementRenderer") }
            .filter { it.path("config", "adPlacementConfig")?.text("kind") == "AD_PLACEMENT_KIND_START" }
            .map { it.fields["renderer"] }
    return content.take(32).sumOf { value ->
        val ads = videoAds(value)
        if (ads.isEmpty()) 15_000L else ads.take(32).sumOf(::adWaitMs)
    }.coerceAtMost(30_000L)
}

private fun videoAds(value: Json?, depth: Int = 0): List<Json.Obj> {
    if (depth > 12) return emptyList()
    return when (value) {
        is Json.Obj -> value.obj("instreamVideoAdRenderer")?.let(::listOf)
            ?: value.fields.values.flatMap { videoAds(it, depth + 1) }
        is Json.Arr -> value.items.flatMap { videoAds(it, depth + 1) }
        else -> emptyList()
    }
}

private fun adWaitMs(ad: Json.Obj): Long {
    ad.long("skipOffsetMilliseconds")?.takeIf { it >= 0 }?.let { return it.coerceAtMost(30_000) }
    val seconds = ad.text("playerVars")?.split('&')?.firstOrNull { it.startsWith("length_seconds=") }
        ?.substringAfter('=')?.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 }
    return seconds?.coerceAtMost(30.0)?.times(1000)?.toLong() ?: 15_000L
}
