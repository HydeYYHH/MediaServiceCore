package com.liskovsoft.youtubeapi.videoinfo.V2

import org.junit.Assert.*
import org.junit.Test

/** Modern inline menus are capabilities of one referenced toolbar, never response-wide hints. */
class CommentInlineMenuSourceIsolationTest {
    private val context = RequestContext(accountId = "synthetic-account", webSession = WebSession("SAPISID=synthetic"))
    private fun json(value: String) = jsonObject(value)!!
    private fun metadata(path: String = "/youtubei/v1/comment/perform_comment_action") =
        """"commandMetadata":{"webCommandMetadata":{"apiUrl":"$path"}}"""
    private fun action(token: String = "owned-delete") = """{${metadata()},"performCommentActionEndpoint":{"action":"$token"}}"""
    private fun item(icon: String = "DELETE", endpoint: String = action()) =
        """{"menuServiceItemRenderer":{"icon":{"iconType":"$icon"},"serviceEndpoint":$endpoint}}"""
    private fun confirmed(endpoint: String = action()) = """{"menuNavigationItemRenderer":{"text":{"simpleText":"Delete"},"icon":{"iconType":"DELETE"},
        "navigationEndpoint":{"commandMetadata":{"webCommandMetadata":{"ignoreNavigation":true}},"confirmDialogEndpoint":{"content":{"confirmDialogRenderer":{
        "title":{"simpleText":"Delete?"},"dialogMessages":[{"runs":[{"text":"Confirm"}]}],"primaryIsCancel":false,
        "confirmButton":{"buttonRenderer":{"text":{"simpleText":"Delete"},"serviceEndpoint":$endpoint}},
        "cancelButton":{"buttonRenderer":{"text":{"simpleText":"Cancel"}}}}}}}}}"""
    private fun menu(items: String = confirmed()) = """{"menuRenderer":{"items":[$items]}}"""
    private fun command(menu: String = menu()) = """{"innertubeCommand":{"menuEndpoint":{"menu":$menu}}}"""
    private fun mutation(key: String, role: String, value: String, type: String = "ENTITY_MUTATION_TYPE_REPLACE") =
        """{"entityKey":"$key","type":"$type","payload":{"$role":$value}}"""
    private fun document(surface: String = """{"key":"surface","menuCommand":${command()}}""",
        surfaceRef: String = "surface", extra: String = "") = """{
        "onResponseReceivedEndpoints":[{"appendContinuationItemsAction":{"targetId":"engagement-panel-comments-section","continuationItems":[
          {"commentViewModel":{"commentKey":"comment-key","toolbarSurfaceKey":"$surfaceRef"}}]}}],
        "frameworkUpdates":{"entityBatchUpdate":{"mutations":[
          ${mutation("comment-key", "commentEntityPayload", """{"key":"comment-key","properties":{"commentId":"owned-id","content":{"content":"owned text"}},"author":{"channelId":"UCowned","displayName":"Owned"}}""")},
          ${mutation("surface", "engagementToolbarSurfaceEntityPayload", surface)}$extra]}}}"""
    private fun opening() = """{"currentVideoEndpoint":{"watchEndpoint":{"videoId":"abcdefghijk"}},"engagementPanelSectionListRenderer":{
        "panelIdentifier":"engagement-panel-comments-section","content":{"continuationItemRenderer":{"continuationEndpoint":{"continuationCommand":{"token":"root"}}}}}}"""
    private inner class Wire(val document: String) : HttpClient {
        val requests = ArrayList<HttpRequest>()
        override fun execute(request: HttpRequest): HttpReply {
            requests += request
            return HttpReply(200, when {
                "/comment/perform_comment_action" in request.url -> """{"status":"STATUS_SUCCEEDED"}"""
                json(request.body).text("videoId") != null -> opening()
                else -> document
            })
        }
        fun thread() = DiscussionSession(AccountSession(context), this).openVideo("abcdefghijk").value!!
    }
    private fun deletedToken(body: String): String? {
        val loaded = loadComments("abcdefghijk", context, "root", Wire(body))
        assertTrue(loaded.error.toString(), loaded.ok)
        return loaded.value!!.comments.single().deleteKey
    }
    private fun rejected(body: String) {
        val wire = Wire(body)
        assertEquals(RequestFailure.PARSE, loadComments("abcdefghijk", context, "root", wire).error?.kind)
        assertEquals(RequestFailure.PARSE, wire.thread().load().error?.kind)
        assertTrue(wire.requests.none { "/comment/" in it.url })
    }

    @Test fun referencedInlineMenuNormalizesAndUsesItsExactAction() {
        val body = document()
        assertEquals("owned-delete", deletedToken(body))
        val projected = ownedCommentResponse(json(body))!!
        assertEquals(projected.encode(), ownedCommentResponse(projected)!!.encode())
        assertFalse(projected.encode().contains("menuCommand"))
        val wire = Wire(body); val thread = wire.thread(); val handle = thread.load().value!!.comments.single()
        assertTrue(handle.permissions.canDelete)
        assertTrue(thread.delete(handle).ok)
        val request = wire.requests.single { "/comment/" in it.url }
        assertEquals(listOf("owned-delete"), json(request.body).arr("actions").map { (it as Json.Str).value })
    }

    @Test fun inlineMenuCannotBorrowAnUnreferencedSurfaceOrMetadata() {
        val shadow = mutation("neighbor", "engagementToolbarSurfaceEntityPayload", """{"key":"neighbor","menuCommand":${command()}}""")
        assertNull(deletedToken(document("""{"key":"surface"}""", extra = ",$shadow")))
        val raw = json(document("""{"key":"surface"}"""))
        val contaminated = Json.Obj(raw.fields + ("responseContext" to json("""{"shadow":${command()}}""")))
        assertNull(deletedToken(contaminated.encode()))
    }

    @Test fun editNavigationIsDroppedWhileTheSameMenuDeleteEntryIsKept() {
        val edit = """{"menuNavigationItemRenderer":{"icon":{"iconType":"EDIT"},"navigationEndpoint":{"unknownEditEndpoint":{"opaque":"synthetic"}}}}"""
        val body = document("""{"key":"surface","menuCommand":${command(menu("$edit,${confirmed()}"))}}""")
        assertEquals("owned-delete", deletedToken(body))
        val projected = ownedCommentResponse(json(body))!!.encode()
        assertFalse(projected.contains("unknownEditEndpoint")); assertFalse(projected.contains("EDIT"))
        assertEquals("owned-delete", commentPage(projected).comments.single().deleteKey)
    }

    @Test fun directDeleteStillUsesOnlyItsOwnEntry() {
        assertEquals("owned-direct", deletedToken(document("""{"key":"surface","menuCommand":${command(menu(item(endpoint = action("owned-direct"))))}}""")))
    }

    @Test fun duplicateOrMismatchedSurfaceCannotIssueDelete() {
        val surface = """{"key":"surface","menuCommand":${command()}}"""
        rejected(document(surface, extra = "," + mutation("surface", "engagementToolbarSurfaceEntityPayload", surface)))
        rejected(document(surface.replace("\"key\":\"surface\"", "\"key\":\"foreign\"")))
        assertNull(deletedToken(document(surface, surfaceRef = "missing")))
    }

    @Test fun deleteIconAndActionCannotBeCombinedAcrossEntries() {
        val items = """{"menuServiceItemRenderer":{"icon":{"iconType":"DELETE"},"text":{"simpleText":"Delete"}}},${item("REPORT", action("owned-report"))}"""
        assertNull(deletedToken(document("""{"key":"surface","menuCommand":${command(menu(items))}}""")))
    }

    @Test fun malformedOrForeignInlineMenuRoutesFailClosed() {
        val base = document()
        for (bad in listOf(
            base.replace("/youtubei/v1/comment/perform_comment_action", "/youtubei/v1/next"),
            base.replace("/youtubei/v1/comment/perform_comment_action", "https://foreign.test/youtubei/v1/comment/perform_comment_action"),
            base.replace("\"apiUrl\":\"/youtubei/v1/comment/perform_comment_action\"", "\"apiUrl\":null"),
            base.replace("\"apiUrl\":\"/youtubei/v1/comment/perform_comment_action\"", "\"apiUrl\":\"/youtubei/v1/comment/perform_comment_action\",\"sendPost\":false"),
            document("""{"key":"surface","menuCommand":null}"""),
            document("""{"key":"surface","menuCommand":{"unknownWrapper":${command()}}}"""),
            document("""{"key":"surface","menuCommand":${command()},"menu":${menu()}}""")
        )) rejected(bad)
    }

    @Test fun incompleteConfirmationDoesNotBorrowItsCancelButtonOrAnUnknownWrapper() {
        val cancel = """{"buttonRenderer":{"serviceEndpoint":${action("cancel-shadow")}}}"""
        val withoutConfirm = confirmed().replace("\"confirmButton\":{\"buttonRenderer\":{\"text\":{\"simpleText\":\"Delete\"},\"serviceEndpoint\":${action()}}},", "")
            .replace("\"cancelButton\":{\"buttonRenderer\":{\"text\":{\"simpleText\":\"Cancel\"}}}", "\"cancelButton\":$cancel")
        assertNull(deletedToken(document("""{"key":"surface","menuCommand":${command(menu(withoutConfirm))}}""")))
    }
}
