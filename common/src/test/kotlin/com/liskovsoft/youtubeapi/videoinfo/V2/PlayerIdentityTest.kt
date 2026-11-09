package com.liskovsoft.youtubeapi.videoinfo.V2

import org.junit.Assert.*
import org.junit.Test

class PlayerIdentityTest {
    @Test fun givesTheTokenProviderTheIdentityUsedOnTheWire() {
        val identities = HashMap<PlayerClient, RequestContext>()
        val original = RequestContext("account", "secret", "visitor", onBehalfOfUser = "brand")
        val tokens = object : PlayerTokenProvider {
            override fun token(request: PlayerTokenRequest): PlayerToken? {
                identities[request.client] = request.context
                return null
            }
        }
        val http = object : HttpClient {
            override fun execute(request: HttpRequest): HttpReply {
                val loggedIn = request.headers["Authorization"] != null
                if (!loggedIn) return HttpReply(503, "{}")
                assertEquals("Bearer secret", request.headers["Authorization"])
                return HttpReply(200, "{\"streamingData\":{\"formats\":[{\"url\":\"https://media\"}]}}")
            }
        }
        assertTrue(loadPlayer("v", original, http, tokens = tokens, excluded = setOf(PlayerClient.TV_TIZEN)).ok)
        assertEquals(original, identities[PlayerClient.TV])
        assertEquals(original.anonymous(), identities[PlayerClient.VISIONOS])
        assertNull(identities[PlayerClient.WEB]!!.onBehalfOfUser)
    }
}
