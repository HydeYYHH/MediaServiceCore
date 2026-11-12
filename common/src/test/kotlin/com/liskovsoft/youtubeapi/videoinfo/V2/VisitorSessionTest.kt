package com.liskovsoft.youtubeapi.videoinfo.V2

import org.junit.Assert.*
import org.junit.Test

class VisitorSessionTest {
    private fun document(config: String) = "<html><script>ytcfg.set($config);</script></html>"
    private fun http(block: (HttpRequest) -> HttpReply) = object : HttpClient {
        override fun execute(request: HttpRequest) = block(request)
    }

    @Test fun immutableVisitorIdentityRestoresOnlyAnonymousStateAndNeverPrintsValues() {
        val session = VisitorSession("YSC=session-value; VISITOR_INFO1_LIVE=cookie-value; SOCS=keep-consent; PREF=f6=400&hl=en", "visitor-value")
        val restored = VisitorSession(session.cookies, session.visitorData)
        val context = restored.requestContext("zh-CN", "TW", 7)
        assertEquals(session.cookies, context.visitorCookie)
        assertEquals(session.visitorData, context.visitor)
        assertEquals("zh-CN", context.language)
        assertEquals("TW", context.region)
        assertEquals(7L, context.generation)
        assertFalse(context.signedIn)
        assertNull(context.webSession)
        assertNull(context.accountId)
        assertNull(context.onBehalfOfUser)
        assertFalse(session.toString().contains("session-value"))
        assertFalse(session.toString().contains("visitor-value"))
        assertEquals("", VisitorSession("", "visitor").cookies)
    }

    @Test fun accountCookiesDuplicatesAndMalformedHeaderValuesAreRejectedAtConstruction() {
        for (cookie in listOf("SAPISID=secret", "SID=secret", "__Secure-3PAPISID=secret", "YSC=a; YSC=b", "YSC=a\r\nSID=secret",
            "YSC=a;", "YSC", "=value", "YSC =value", "YSC=contains space", "YSC=quoted\\value", "YSC=\"quoted\"",
            "YSC=comma,value", "YSC=nonascii\u00e9", "YSC=a; Domain=youtube.com")) {
            try { VisitorSession(cookie, "visitor"); fail("Invalid cookie accepted") } catch (_: IllegalArgumentException) { }
        }
        for (visitor in listOf("", " ", "visitor\n", "visitor value", "x".repeat(8193))) {
            try { VisitorSession("YSC=fixture", visitor); fail("Invalid visitor data accepted") } catch (_: IllegalArgumentException) { }
        }
    }

    @Test fun bootstrapUsesOneFixedBrowserDocumentAndMergesOnlySafeRootCookiesWithoutChangingConsent() {
        val calls = ArrayList<HttpRequest>()
        val context = VisitorSession("YSC=delete; VISITOR_INFO1_LIVE=old; SOCS=keep-socs; CONSENT=keep-consent", "old-visitor")
            .requestContext("zh-CN", "TW")
        val responseCookies = "VISITOR_INFO1_LIVE=new; Domain=.youtube.com; Path=/; Secure; HttpOnly, " +
            "YSC=gone; Max-Age=0; Path=/, " +
            "PREF=f6=400&hl=en; Domain=www.youtube.com; Path=/; Expires=Tue, 19 Jan 2038 03:14:07 GMT, " +
            "__Secure-ROLLOUT_TOKEN=rollout; Secure; Domain=.youtube.com; Path=/, " +
            "SOCS=foreign; Domain=evil.test; Path=/, CONSENT=foreign; Domain=google.com; Path=/, " +
            "GPS=wrong-path; Domain=.youtube.com; Path=/api, SAPISID=must-not-retain; Domain=.youtube.com; Path=/, " +
            "GPS=valid; Domain=.youtube.com; Path=/; Secure"
        val result = loadVisitorSession(context, http { request ->
            calls += request
            HttpReply(200, document("""{"VISITOR_DATA":"fresh-visitor","LOGGED_IN":false}"""), mapOf("Set-Cookie" to responseCookies))
        }).value!!
        assertEquals(1, calls.size)
        val request = calls.single()
        assertEquals("GET", request.method)
        assertEquals("https://www.youtube.com/?hl=zh-CN&gl=TW", request.url)
        assertEquals(setOf("User-Agent", "Cookie"), request.headers.keys)
        assertTrue(request.headers.getValue("User-Agent").contains("Mozilla/5.0"))
        assertEquals(context.visitorCookie, request.headers["Cookie"])
        assertEquals("fresh-visitor", result.visitorData)
        val jar = parseVisitorCookies(result.cookies)
        assertEquals("new", jar["VISITOR_INFO1_LIVE"])
        assertEquals("f6=400&hl=en", jar["PREF"])
        assertEquals("rollout", jar["__Secure-ROLLOUT_TOKEN"])
        assertEquals("valid", jar["GPS"])
        assertEquals("keep-socs", jar["SOCS"])
        assertEquals("keep-consent", jar["CONSENT"])
        assertFalse(jar.containsKey("YSC"))
        assertFalse(jar.containsKey("SAPISID"))
    }

    @Test fun bootstrapAcceptsNestedVisitorConfigAndSkipsQuotedOrCommentedSettersWithoutEvaluatingScripts() {
        val html = """<script>
            const quoted = 'ytcfg.set({"VISITOR_DATA":"quoted"})';
            // ytcfg.set({"VISITOR_DATA":"commented"});
            /* ytcfg.set({"LOGGED_IN":true}); */
            ytcfg.set({"INNERTUBE_CONTEXT":{"client":{"visitorData":"nested-visitor"}},"text":"braces } and {"});
            throw new Error('Scripts are data to the core');
            </script>"""
        var calls = 0
        val result = loadVisitorSession(RequestContext(), http = http { request ->
            calls++
            assertEquals(setOf("User-Agent"), request.headers.keys)
            HttpReply(200, html)
        })
        assertEquals("nested-visitor", result.value!!.visitorData)
        assertEquals("", result.value!!.cookies)
        assertEquals(1, calls)
    }

    @Test fun malformedConfigsMissingIdentityAndUnexpectedSignedInPagesStayFailures() {
        for (body in listOf("<script>var ytInitialData={};</script>", document("{"),
            "<script>ytcfg.set({VISITOR_DATA:'not JSON'});</script>",
            "<script>ytcfg.set({\"VISITOR_DATA\":\"not-a-literal-argument\"}+execute());</script>")) {
            assertEquals("missing_visitor_config", loadVisitorSession(RequestContext(), http = http { HttpReply(200, body) }).error!!.code)
        }
        assertEquals("missing_visitor_data", loadVisitorSession(RequestContext(), http = http { HttpReply(200, document("{}")) }).error!!.code)
        assertEquals("invalid_visitor_session", loadVisitorSession(RequestContext(), http = http {
            HttpReply(200, document("""{"VISITOR_DATA":"visitor with spaces"}"""))
        }).error!!.code)
        assertEquals("unexpected_signed_in_document", loadVisitorSession(RequestContext(), http = http {
            HttpReply(200, document("""{"VISITOR_DATA":"visitor","LOGGED_IN":true}"""))
        }).error!!.code)
    }

    @Test fun signedDelegatedAndCredentialBearingInputsAreRejectedBeforeIoAndTransportFailuresRetainClassification() {
        var calls = 0
        val never = http { calls++; error("Invalid identities must not reach the network") }
        for (context in listOf(RequestContext(webSession = WebSession("SAPISID=secret")), RequestContext(onBehalfOfUser = "brand"),
            RequestContext(accountId = "owner"))) {
            assertEquals("visitor_requires_anonymous_context", loadVisitorSession(context, never).error!!.code)
        }
        assertEquals("invalid_visitor_cookies", loadVisitorSession(RequestContext(visitorCookie = "SAPISID=secret"), never).error!!.code)
        assertEquals(0, calls)
        assertEquals(RequestFailure.HTTP, loadVisitorSession(RequestContext(), http = http { HttpReply(429, "") }).error!!.kind)
        assertEquals(RequestFailure.AUTH, loadVisitorSession(RequestContext(), http = http { HttpReply(403, "") }).error!!.kind)
        assertEquals(RequestFailure.VERIFICATION, loadVisitorSession(RequestContext(), http = http {
            HttpReply(302, "", mapOf("Location" to "https://www.google.com/sorry/index"))
        }).error!!.kind)
    }

    @Test fun expiredAndUnsafeResponseCookiesNeverOverrideRestoredAnonymousState() {
        val context = VisitorSession("YSC=old; GPS=old; CONSENT=keep", "visitor").requestContext()
        val reply = loadVisitorSession(context, http {
            HttpReply(200, document("""{"VISITOR_DATA":"visitor"}"""), mapOf("Set-Cookie" to
                "YSC=deleted; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Path=/, GPS=wrong; Domain=.com; Path=/, " +
                "CONSENT=wrong; Domain=.youtube.com; Domain=evil.test; Path=/, " +
                "__Secure-ROLLOUT_TOKEN=insecure; Path=/, PREF=wrong; Path=/api, VISITOR_INFO1_LIVE=bad value; Path=/"))
        }).value!!
        val jar = parseVisitorCookies(reply.cookies)
        assertNull(jar["YSC"])
        assertEquals("old", jar["GPS"])
        assertEquals("keep", jar["CONSENT"])
        assertNull(jar["__Secure-ROLLOUT_TOKEN"])
        assertNull(jar["PREF"])
        assertNull(jar["VISITOR_INFO1_LIVE"])
    }
}
