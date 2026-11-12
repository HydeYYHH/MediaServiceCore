package com.liskovsoft.youtubeapi.videoinfo.V2

import java.net.URI
import java.util.Collections

data class ArtworkVariant @JvmOverloads constructor(val url: String, val width: Int? = null, val height: Int? = null) {
    init {
        require(url.isNotBlank() && url.none { it == '\r' || it == '\n' })
        require(width == null || width > 0)
        require(height == null || height > 0)
    }
    override fun toString() = "ArtworkVariant(width=$width, height=$height)"
}

/** Immutable server-provided image variants. Selection performs no network requests or URL rewriting. */
class Artwork(variants: List<ArtworkVariant>) {
    val variants: List<ArtworkVariant> = Collections.unmodifiableList(variants.distinct().toList())
    val best: ArtworkVariant? get() = variants.maxWithOrNull(compareBy<ArtworkVariant> { area(it) }
        .thenBy { maxOf(it.width ?: 0, it.height ?: 0) }) ?: variants.lastOrNull()

    /** Smallest known variant meeting both bounds, otherwise the largest available variant. */
    @JvmOverloads fun select(minWidth: Int = 0, minHeight: Int = 0): ArtworkVariant? {
        require(minWidth >= 0 && minHeight >= 0)
        if (minWidth == 0 && minHeight == 0) return best
        return variants.filter { it.width != null && it.height != null && it.width >= minWidth && it.height >= minHeight }
            .minWithOrNull(compareBy<ArtworkVariant> { area(it) }.thenBy { maxOf(it.width ?: 0, it.height ?: 0) }) ?: best
    }
    private fun area(value: ArtworkVariant) = (value.width ?: 0).toLong() * (value.height ?: 0)
    override fun equals(other: Any?) = other is Artwork && variants == other.variants
    override fun hashCode() = variants.hashCode()
    override fun toString() = "Artwork(variantCount=${variants.size})"
}

/** A completed preparation result: missing artwork has a fixed reason, never another host-side request. */
data class AuthorInfo(val channelId: String?, val name: String?, val avatar: Artwork?, val unavailableReason: String?)
/** Pure presentation wrapper; the original post and its native commands are retained unchanged. */
data class PreparedPost(val post: PostItem, val author: AuthorInfo) {
    override fun toString() = "PreparedPost(authorArtworkAvailable=${author.avatar != null})"
}

/** What a card can display, without equating collection ownership with content authorship. */
enum class DisplayBylineRole { ITEM_AUTHOR, COLLECTION_OWNER, UNAVAILABLE }

data class DisplayByline(val author: AuthorInfo, val role: DisplayBylineRole) {
    override fun toString() = "DisplayByline(role=$role, artworkAvailable=${author.avatar != null})"
}

class PreparedMediaItem private constructor(val item: MediaItem, val author: AuthorInfo,
    val displayByline: DisplayByline) {
    constructor(item: MediaItem, author: AuthorInfo) : this(item, author, itemByline(author))

    /** No lookup or command execution; non-post cards have no prepared post. */
    val post: PreparedPost? get() = if (item.kind == "post") item.post?.let { PreparedPost(it, author) } else null

    internal fun withCollectionOwner(owner: AuthorInfo): PreparedMediaItem =
        PreparedMediaItem(item, author, DisplayByline(owner, DisplayBylineRole.COLLECTION_OWNER))

    operator fun component1() = item
    operator fun component2() = author
    /** A changed or cloned card/author cannot inherit private collection provenance. */
    fun copy(item: MediaItem = this.item, author: AuthorInfo = this.author): PreparedMediaItem =
        if (item === this.item && author === this.author) PreparedMediaItem(item, author, displayByline)
        else PreparedMediaItem(item, author)

    override fun equals(other: Any?) = other is PreparedMediaItem && item == other.item && author == other.author && displayByline == other.displayByline
    override fun hashCode() = 31 * (31 * item.hashCode() + author.hashCode()) + displayByline.hashCode()
    override fun toString() = "PreparedMediaItem(item=$item, author=$author)"
}

private fun itemByline(author: AuthorInfo) = DisplayByline(author,
    if (author.channelId != null || !author.name.isNullOrBlank() || author.avatar != null) DisplayBylineRole.ITEM_AUTHOR
    else DisplayBylineRole.UNAVAILABLE)
data class PreparedPage(val items: List<PreparedMediaItem>, val requestCount: Int)

/** Adapt an already-owned post, without reparsing it or dropping its native command objects. */
internal fun PostItem.preparationItem() = MediaItem("post", postId, text, author,
    images.firstNotNullOfOrNull(::artworkUrl), null, channelId = authorId, published = published,
    authorThumbnail = authorAvatar, post = this)

internal fun artworkUrl(value: String?): String? {
    val url = value?.trim()?.takeIf(String::isNotEmpty)?.let { if (it.startsWith("//")) "https:$it" else it } ?: return null
    return runCatching { URI(url) }.getOrNull()?.takeIf {
        it.scheme.equals("https", true) && !it.host.isNullOrBlank() && it.rawUserInfo == null && it.port in listOf(-1, 443)
    }?.let { url }
}

internal fun readArtwork(image: Json.Obj?): Artwork? {
    image ?: return null
    val variants = (image.arr("thumbnails") + image.arr("sources")).mapNotNull { source ->
        val variant = source as? Json.Obj ?: return@mapNotNull null
        val url = artworkUrl(variant.text("url")) ?: return@mapNotNull null
        ArtworkVariant(url, variant.int("width")?.takeIf { it > 0 }, variant.int("height")?.takeIf { it > 0 })
    }
    return variants.takeIf { it.isNotEmpty() }?.let(::Artwork)
}

internal fun artworkFromUrl(url: String?): Artwork? = artworkUrl(url)?.let { Artwork(listOf(ArtworkVariant(it))) }

internal fun channelArtwork(root: Json.Obj): Artwork? {
    val metadata = root.objects("channelMetadataRenderer").firstOrNull()
    val header = root.objects("c4TabbedHeaderRenderer").firstOrNull()
    val channelHeader = root.objects("channelHeaderRenderer").firstOrNull()
    val modern = root.objects("pageHeaderViewModel").firstOrNull()
    return readArtwork(metadata?.obj("avatar")) ?: readArtwork(header?.obj("avatar"))
        ?: readArtwork(channelHeader?.obj("avatar"))
        ?: readArtwork(modern?.path("image", "decoratedAvatarViewModel", "avatar", "avatarViewModel", "image"))
        ?: readArtwork(modern?.path("image", "avatarViewModel", "image"))
}
