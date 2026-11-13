package com.liskovsoft.youtubeapi.videoinfo.V2

import java.io.ByteArrayOutputStream
import java.net.URLEncoder
import java.util.Base64

data class CommentTranslation(val text: String, val targetLanguage: String)

/** YouTube.js: ProtoUtils.encodeCommentActionParams and CommentView.translate. */
@JvmOverloads
fun translateComment(text: String, targetLanguage: String, context: RequestContext = RequestContext(),
    http: HttpClient = JvmHttpClient(), commentId: String? = null, videoId: String? = null): ReadResult<CommentTranslation> {
    if (text.isBlank() || text.length > 10000 ||
        !Regex("[A-Za-z]{2,3}(?:-[A-Za-z0-9]{2,8}){0,3}").matches(targetLanguage) ||
        commentId?.let { it.isBlank() || it.length > 256 || it.any(Char::isISOControl) } == true ||
        videoId?.let { !Regex("[A-Za-z0-9_-]{11}").matches(it) } == true)
        return ReadResult(error = RequestError(RequestFailure.INVALID, "invalid_comment_translation"))
    // InnerTube's translation action rejects emoji. The caller retains the original comment.
    val translatable = text.replace(Regex("[^\\p{L}\\p{N}\\p{P}\\p{Z}]"), "")
    if (translatable.isBlank())
        return ReadResult(error = RequestError(RequestFailure.UNSUPPORTED, "comment_has_no_translatable_text"))
    val action = translationAction(translatable, targetLanguage, commentId, videoId)
    val response = requestObject(youtubeQuery(YOUTUBE_API + "comment/perform_comment_action",
        "\"actions\":" + Json.Arr(listOf(Json.Str(action))).encode(), context), http)
    if (!response.ok) return ReadResult(error = response.error)
    val mutations = response.value!!.path("frameworkUpdates", "entityBatchUpdate")?.arr("mutations").orEmpty()
    val translations = mutations.mapNotNull { mutation ->
        val entity = (mutation as? Json.Obj)?.path("payload", "commentEntityPayload") ?: return@mapNotNull null
        val actualId = entity.obj("properties")?.text("commentId")
        if (commentId != null && actualId != null && actualId != commentId) return@mapNotNull null
        entity.obj("translatedContent")?.text("content")?.takeIf(String::isNotBlank)
    }
    return if (translations.size == 1) ReadResult(CommentTranslation(translations.single(), targetLanguage))
        else ReadResult(error = RequestError(RequestFailure.PARSE, "unknown_comment_translation"))
}

private fun translationAction(text: String, language: String, commentId: String?, videoId: String?): String {
    fun message(write: TranslationProto.() -> Unit) = TranslationProto().apply(write).bytes()
    val translation = message {
        string(2, commentId ?: " ")
        bytes(3, message { bytes(1, message { string(1, text) }) })
        string(4, language)
    }
    val action = message {
        integer(1, 22)
        if (commentId == null) integer(2, 2)
        string(3, commentId ?: " ")
        string(5, videoId ?: " ")
        string(23, " ")
        bytes(31, translation)
    }
    return URLEncoder.encode(Base64.getUrlEncoder().encodeToString(action), "UTF-8")
}

private class TranslationProto {
    private val output = ByteArrayOutputStream()
    private fun number(value: Int) {
        var remaining = value
        while (remaining >= 128) {
            output.write((remaining and 127) or 128)
            remaining = remaining ushr 7
        }
        output.write(remaining)
    }
    fun integer(field: Int, value: Int) { number(field shl 3); number(value) }
    fun string(field: Int, value: String) = bytes(field, value.toByteArray(Charsets.UTF_8))
    fun bytes(field: Int, value: ByteArray) {
        number((field shl 3) or 2); number(value.size); output.write(value)
    }
    fun bytes(): ByteArray = output.toByteArray()
}
