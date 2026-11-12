package com.liskovsoft.youtubeapi.videoinfo.V2

/** Comment presentation without continuation, creation, moderation or rating tokens. */
class DiscussionComment internal constructor(comment: Comment) {
    val id = comment.id
    val author = comment.author
    val authorId = comment.authorId
    val authorPhoto = comment.authorPhoto
    val text = comment.text
    val imageAttachment = comment.imageAttachment
    val voiceReply = comment.voiceReply
    val hasDisplayableContent = comment.hasDisplayableContent
    val runs = comment.runs.toList()
    val published = comment.published
    val likeCount = comment.likeCount
    val viewerRating = comment.viewerRating
    val parentId = comment.parentId
    val replyCount = comment.replyCount
    val isCreator = comment.isCreator
    val isVerified = comment.isVerified
    val isPinned = comment.isPinned
    val isHearted = comment.isHearted
    override fun toString() = "DiscussionComment"
}

/** Permissions observed on the most recent successful read of this comment. */
class CommentPermissions internal constructor(val canReply: Boolean, val canDelete: Boolean,
    val canLoadReplies: Boolean, val canResolveActions: Boolean, val votes: Set<CommentVote>) {
    val canRate: Boolean get() = votes.isNotEmpty()
    override fun toString() = "CommentPermissions"
}

/** A comment belonging to exactly one discussion and account generation. */
class CommentHandle internal constructor(internal val owner: CommentThread, internal val entry: Long,
    private val initial: DiscussionComment) {
    val content: DiscussionComment get() = owner.content(this) ?: initial
    val permissions: CommentPermissions get() = owner.permissions(this)
    override fun toString() = "CommentHandle"
}

/** An opaque root/reply continuation; a sort or a root refresh invalidates old cursors. */
class CommentCursor internal constructor(internal val owner: CommentThread, internal val revision: Long,
    internal val route: DiscussionRoute, internal val replies: Boolean) {
    override fun toString() = "CommentCursor"
}

/** A server-provided sort choice. Labels are displayed as returned, never interpreted as English enums. */
class CommentSortOrder internal constructor(internal val owner: CommentThread, internal val revision: Long,
    internal val route: DiscussionRoute, val title: String?, val selected: Boolean) {
    override fun toString() = "CommentSortOrder"
}

class CommentThreadPage internal constructor(val comments: List<CommentHandle>, val next: CommentCursor?,
    val sortOrders: List<CommentSortOrder>, val total: String?, val turnedOff: Boolean, val canCreate: Boolean) {
    override fun toString() = "CommentThreadPage"
}

/** Unconfirmed writes retain the draft/intent; this API never retries a mutation automatically. */
class CommentMutation internal constructor(val comment: CommentHandle?, val state: ActionState, val httpStatus: Int?) {
    val confirmed: Boolean get() = state == ActionState.CONFIRMED
    override fun toString() = "CommentMutation(state=$state)"
}
