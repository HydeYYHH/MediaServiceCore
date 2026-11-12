package com.liskovsoft.youtubeapi.videoinfo.V2

import org.junit.Assert.*
import org.junit.Test

class PlaybackIdentityTest {
    @Test fun separatesCredentialsAndLocaleWithoutRequiringAnAccountId() {
        val contexts = listOf(RequestContext(), RequestContext(webSession = WebSession("SAPISID=first-secret")),
            RequestContext(webSession = WebSession("SAPISID=second-secret")), RequestContext(language = "zh"),
            RequestContext(region = "JP"), RequestContext(onBehalfOfUser = "brand"))
        assertEquals(contexts.size, contexts.map { it.scope() }.distinct().size)
        assertFalse(contexts[1].scope().contains("first-secret"))
        var calls = 0
        val http = object : HttpClient {
            override fun execute(request: HttpRequest): HttpReply {
                calls++
                return HttpReply(200, "{\"streamingData\":{\"formats\":[{\"url\":\"https://media/$calls\"}]}}")
            }
        }
        val session = PlaybackSession(http)
        val results = contexts.map { session.load("video", it).value!! }
        assertEquals(contexts.size, calls)
        assertEquals(contexts, results.map { it.source!!.context })
        session.load("video", contexts.last())
        assertEquals(contexts.size, calls)
    }
}
