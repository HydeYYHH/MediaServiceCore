package com.liskovsoft.youtubeapi.videoinfo.V2

import org.junit.Assert.*
import org.junit.Test

/** Network/domain boundaries for the exact known display-only BODY list slot. No sockets. */
class CommentFilterContextSourceIsolationTest {
    private val context=RequestContext()
    private val video="abcdefghijk"
    private fun legacy(id:String="owned",text:String="owned text")="""{"commentRenderer":{"commentId":"$id","contentText":{"simpleText":"$text"},"authorEndpoint":{"browseEndpoint":{"browseId":"UCown"}}}}"""
    private fun cursor()="""{"continuationItemRenderer":{"continuationEndpoint":{"commandMetadata":{"webCommandMetadata":{"apiUrl":"/youtubei/v1/browse"}},"continuationCommand":{"token":"owned-next"}}}}"""
    private val trap="""{"commentFilterContextViewModel":{"commentRenderer":{"commentId":"shadow","contentText":{"simpleText":"shadow text"}},
        "createCommentEndpoint":{"createCommentParams":"shadow-create"},"createCommentParams":"shadow-loose-create",
        "sortFilterSubMenuRenderer":{"subMenuItems":[{"title":"shadow","serviceEndpoint":{"continuationCommand":{"token":"shadow-sort"}}}]},
        "continuationItemRenderer":{"continuationEndpoint":{"commandMetadata":{"webCommandMetadata":{"apiUrl":"https://foreign.invalid/youtubei/v1/browse"}},"continuationCommand":{"token":"shadow-next"}}},
        "frameworkUpdates":{"entityBatchUpdate":{"mutations":[{"entityKey":"shadow-key","type":"ENTITY_MUTATION_TYPE_REPLACE","payload":{"commentEntityPayload":{"key":"shadow-key","properties":{"commentId":"shadow"}}}}]}}}}"""
    private fun body(items:String)="""{"onResponseReceivedEndpoints":[{"reloadContinuationItemsCommand":{"slot":"RELOAD_CONTINUATION_SLOT_BODY","targetId":"engagement-panel-comments-section","continuationItems":[$items]}}]}"""
    private fun opening()="""{"currentVideoEndpoint":{"watchEndpoint":{"videoId":"$video"}},"videoDetails":{"videoId":"$video"},
        "engagementPanelSectionListRenderer":{"panelIdentifier":"engagement-panel-comments-section","content":{"continuationItemRenderer":{"continuationEndpoint":{"continuationCommand":{"token":"root"}}}}}}"""
    private inner class Wire(var document:String):HttpClient {
        val requests=ArrayList<HttpRequest>()
        override fun execute(request:HttpRequest):HttpReply {
            requests+=request
            val input=jsonObject(request.body)!!
            return HttpReply(200,when {input.text("videoId")!=null->opening();input.text("continuation")=="owned-next"->body(legacy("next","next text"));else->document})
        }
        fun thread()=DiscussionSession(AccountSession(context),this).openVideo(video).value!!
    }
    private fun rejected(document:String) {
        val wire=Wire(document)
        assertEquals(RequestFailure.PARSE,loadComments(video,context,"root",wire).error?.kind)
        assertEquals(1,wire.requests.size)
        val thread=wire.thread();assertEquals(RequestFailure.PARSE,thread.load().error?.kind)
        assertEquals(3,wire.requests.size);assertTrue(wire.requests.none{"/comment/" in it.url})
    }
    @Test fun mixedOwnedPageKeepsItsCommentAndExactIssuedBrowseCursor() {
        val wire=Wire(body(listOf(legacy(),trap,cursor()).joinToString(",")))
        val plain=loadComments(video,context,"root",wire);assertTrue(plain.ok)
        assertEquals(listOf("owned"),plain.value!!.comments.map{it.id});assertEquals("owned-next",plain.value!!.nextKey)
        val thread=wire.thread();val page=thread.load();assertTrue(page.ok)
        assertEquals("owned text",page.value!!.comments.single().content.text);assertNotNull(page.value!!.next)
        val next=thread.next(page.value!!.next!!);assertTrue(next.ok);assertEquals("next text",next.value!!.comments.single().content.text)
        assertTrue(wire.requests.last().url.substringBefore('?').endsWith("/browse"))
        assertEquals("owned-next",jsonObject(wire.requests.last().body)!!.text("continuation"))
    }
    @Test fun displaySubtreeCannotMintCreationSortOrHandlePermissions() {
        val wire=Wire(body(legacy()+","+trap));val thread=wire.thread();val page=thread.load().value!!
        assertEquals(1,page.comments.size);assertFalse(page.canCreate);assertTrue(page.sortOrders.isEmpty());assertNull(page.next)
        val handle=page.comments.single();assertFalse(handle.permissions.canDelete);assertFalse(handle.permissions.canRate);assertFalse(handle.permissions.canReply)
        val before=wire.requests.size;assertFalse(thread.create("synthetic").ok);assertFalse(thread.delete(handle).ok)
        assertEquals(before,wire.requests.size)
        val callback=loadComments(video,null){query->200 to if(jsonObject(query.body)?.text("videoId")!=null)opening() else wire.document}
        assertEquals(listOf("owned"),callback.comments.map{it.id});assertNull(callback.createKey);assertTrue(callback.sortOptions.isEmpty())
    }
    @Test fun explicitDisplayOnlyBodyDoesNotBorrowNestedRecordsOrCursor() {
        val wire=Wire(body(trap));val page=wire.thread().load();assertTrue(page.ok)
        assertTrue(page.value!!.comments.isEmpty());assertFalse(page.value!!.canCreate);assertTrue(page.value!!.sortOrders.isEmpty());assertNull(page.value!!.next)
        assertEquals(2,wire.requests.size)
    }
    @Test fun wrongDisplayTypesAndMixedOrUnknownWrappersRemainParseFailures() {
        for(value in listOf("null","[]","\"text\"","true","1"))rejected(body("""{"commentFilterContextViewModel":$value}"""))
        rejected(body("""{"commentFilterContextViewModel":{},"commentRenderer":{"commentId":"mixed"}}"""))
        rejected(body("""{"commentFilterContextViewModel":{},"trackingParams":"mixed"}"""))
        rejected(body("""{"futureCommentRenderer":{"commentFilterContextViewModel":{}}}"""))
    }
    @Test fun missingBodyListAndFilterInOtherRolesNeverBecomeEmptyPages() {
        rejected("""{"onResponseReceivedEndpoints":[{"reloadContinuationItemsCommand":{"slot":"RELOAD_CONTINUATION_SLOT_BODY","targetId":"engagement-panel-comments-section"}}]}""")
        rejected("""{"onResponseReceivedEndpoints":[{"reloadContinuationItemsCommand":{"slot":"RELOAD_CONTINUATION_SLOT_BODY","targetId":"engagement-panel-comments-section","commentFilterContextViewModel":{},"continuationItems":[]}}]}""")
        rejected("""{"onResponseReceivedEndpoints":[{"reloadContinuationItemsCommand":{"slot":"RELOAD_CONTINUATION_SLOT_HEADER","targetId":"engagement-panel-comments-section","continuationItems":[$trap]}},{"reloadContinuationItemsCommand":{"slot":"RELOAD_CONTINUATION_SLOT_BODY","targetId":"engagement-panel-comments-section","continuationItems":[]}}]}""")
    }
    @Test fun modernJoinsStayOwnAndProjectionRemainsIdempotent() {
        val modern="""{"commentViewModel":{"commentKey":"own-key","commentId":"own-modern"}}"""
        val root=jsonObject(body(modern+","+trap))!!
        val withEntity=Json.Obj(root.fields+("frameworkUpdates" to jsonObject("""{"entityBatchUpdate":{"mutations":[{"entityKey":"own-key","type":"ENTITY_MUTATION_TYPE_REPLACE","payload":{"commentEntityPayload":{"key":"own-key","properties":{"commentId":"own-modern","content":{"content":"own modern text"}}}}}]}}""")!!))
        val wire=Wire(withEntity.encode());val page=wire.thread().load();assertTrue(page.ok)
        assertEquals(listOf("own-modern"),page.value!!.comments.map{it.content.id});assertFalse(page.value!!.canCreate)
        val projected=ownedCommentResponse(withEntity)!!
        assertEquals(projected.encode(),ownedCommentResponse(projected)!!.encode());assertFalse(projected.encode().contains("commentFilterContextViewModel"));assertFalse(projected.encode().contains("shadow"))
    }
}
