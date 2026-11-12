package com.liskovsoft.youtubeapi.videoinfo.V2

import org.junit.Assert.*
import org.junit.Test

class PlayerIdentityTest {
    @Test fun givesTheTokenProviderTheExactIdentityUsedByEveryPlayerClient() {
        val original = RequestContext(accountId = "account", webSession = WebSession("SAPISID=fixture", 1), visitor = "visitor", onBehalfOfUser = "brand")
        for (client in PlayerClient.values()) {
            val cookieless = client in setOf(PlayerClient.VISIONOS, PlayerClient.ANDROID_VR)
            val expected = if (cookieless) original.anonymous() else original
            val tokens = object : PlayerTokenProvider {
                override fun token(request: PlayerTokenRequest): PlayerToken? {
                    assertEquals(client, request.client)
                    assertEquals(expected, request.context)
                    return null
                }
            }
            val http = object : HttpClient {
                override fun execute(request: HttpRequest): HttpReply {
                    if (cookieless) {
                        assertNull(request.headers["Authorization"])
                        assertNull(request.headers["Cookie"])
                    } else {
                        assertEquals("SAPISID=fixture", request.headers["Cookie"])
                        assertEquals("1", request.headers["X-Goog-AuthUser"])
                        assertEquals("brand", request.headers["X-Goog-PageId"])
                        assertEquals("brand", jsonObject(request.body)!!.path("context", "user")!!.text("onBehalfOfUser"))
                    }
                    return HttpReply(200, """{"streamingData":{"formats":[{"url":"https://media"}]}}""")
                }
            }
            assertTrue(client.toString(), loadPlayer("video", original, http, tokens = tokens,
                excluded = PlayerClient.values().toSet() - client).ok)
        }
    }
}
