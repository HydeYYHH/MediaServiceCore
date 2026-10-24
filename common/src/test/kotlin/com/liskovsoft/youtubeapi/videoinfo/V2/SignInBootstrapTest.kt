package com.liskovsoft.youtubeapi.videoinfo.V2
import org.junit.Assert.*
import org.junit.Test
class SignInBootstrapTest {
    @Test fun resolvesTheBaseJsBundleFromTheTvPage() {
        assertEquals("https://www.youtube.com/s/tv/js/base.js",
            baseJsUrl("""<script id="base-js" src="/s/tv/js/base.js"></script>"""))
        assertEquals("https://www.youtube.com/s/tv/js/base.js",
            baseJsUrl("""<script src="//www.youtube.com/s/tv/js/base.js" id="base-js"></script>"""))
        assertEquals("https://www.youtube.com/s/tv/js/m=base",
            baseJsUrl("""var el = document.createElement('script'); el.src = '/s/tv/js/m=base';"""))
        assertNull(baseJsUrl("""<script id="base-js" src="https://evil.example.com/base.js"></script>"""))
        assertNull(baseJsUrl("<html>no script here</html>"))
    }
    @Test fun takesTheFirstCredentialPairBecauseLaterOnesAreWrong() {
        val js = """a={clientId:"111-abc.apps.googleusercontent.com",b:"secretOne"};""" +
            """c={clientId:"222-def.apps.googleusercontent.com",d:"secretTwo"};"""
        val credentials = signInCredentials(js)!!
        assertEquals("111-abc.apps.googleusercontent.com", credentials.clientId)
        assertEquals("secretOne", credentials.clientSecret)
        assertNull(signInCredentials("no credentials here"))
        assertFalse(credentials.toString().contains("secretOne"))
    }
    @Test fun fetchesTheTvPageThenTheBundle() {
        val seen = ArrayList<String>()
        val http = object : HttpClient { override fun execute(request: HttpRequest): HttpReply {
            seen.add(request.url)
            return when {
                request.url == "https://www.youtube.com/tv" ->
                    HttpReply(200, """<script id="base-js" src="/s/tv/js/base.js"></script>""")
                request.url == "https://www.youtube.com/s/tv/js/base.js" ->
                    HttpReply(200, """a={clientId:"111-abc.apps.googleusercontent.com",b:"secretOne"};""")
                else -> HttpReply(404, "")
            }
        }}
        val credentials = loadSignInCredentials(http).value!!
        assertEquals("111-abc.apps.googleusercontent.com", credentials.clientId)
        assertEquals(listOf("https://www.youtube.com/tv", "https://www.youtube.com/s/tv/js/base.js"), seen)
    }
    @Test fun reportsParseFailures() {
        val noBundle = loadSignInCredentials(object : HttpClient {
            override fun execute(request: HttpRequest) = HttpReply(200, "<html/>")
        })
        assertFalse(noBundle.ok)
        assertEquals("base_js_not_found", noBundle.error!!.code)
    }
}
