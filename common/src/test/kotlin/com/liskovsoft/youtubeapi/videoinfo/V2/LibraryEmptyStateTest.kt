package com.liskovsoft.youtubeapi.videoinfo.V2

import org.junit.Assert.*
import org.junit.Test

class LibraryEmptyStateTest {
    @Test fun emptyUploadsExposeTheServersPromoMessageInsteadOfUnknownOrInventedCards() {
        val context = RequestContext(webSession = WebSession("SAPISID=fixture"), accountId = "fixture-account")
        val body = """{"contents":{"tvBrowseRenderer":{"content":{"tvSurfaceContentRenderer":{
            "content":{"genericPromoRenderer":{"bodyText":{"runs":[{"text":"No uploaded videos in this fixture"}]}}},
            "header":{"tvSurfaceHeaderRenderer":{"title":{"runs":[{"text":"Your videos"}]}}}
        }}}}}"""
        val result = loadLibrary(LibrarySection.MY_VIDEOS, context, http = object : HttpClient {
            override fun execute(request: HttpRequest): HttpReply {
                assertEquals("TVHTML5", jsonObject(request.body)!!.path("context", "client")!!.text("clientName"))
                assertTrue(request.headers["Authorization"]!!.startsWith("SAPISIDHASH "))
                return HttpReply(200, body)
            }
        })
        assertTrue(result.ok)
        assertTrue(result.value!!.items.isEmpty())
        assertNull(result.value!!.nextKey)
        assertEquals(listOf("No uploaded videos in this fixture"), result.value!!.messages)
    }
}
