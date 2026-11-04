package com.liskovsoft.youtubeapi.videoinfo.V2
import org.junit.Assert.*
import org.junit.Test
class ChatSendTest {
    @Test fun buildsTheSendMessageRequest() {
        var seen: HttpRequest? = null
        val http = object : HttpClient { override fun execute(request: HttpRequest): HttpReply {
            seen = request
            return HttpReply(200, """{"responseContext":{},"actions":[{"addChatItemAction":{}}]}""")
        }}
        val reply = sendChatMessage("chat-key", "hello \"chat\"", RequestContext("a", "token"), http,
            clientMessageId = "fixed-id")
        assertTrue(reply.ok)
        val request = seen!!
        assertTrue(request.url.endsWith("live_chat/send_message"))
        assertEquals("Bearer token", request.headers["Authorization"])
        val body = jsonObject(request.body)!!
        assertEquals("chat-key", body.text("params"))
        assertEquals("fixed-id", body.text("clientMessageId"))
        val runs = body.path("richMessage")!!.arr("textSegments")
        assertEquals("hello \"chat\"", (runs.single() as Json.Obj).text("text"))
    }
    @Test fun validatesInputAndRequiresSignIn() {
        val http = object : HttpClient { override fun execute(request: HttpRequest) = HttpReply(200, "{}") }
        val signedIn = RequestContext("a", "token")
        assertEquals("missing_chat_key", sendChatMessage("", "hi", signedIn, http).error!!.code)
        assertEquals("invalid_chat_message", sendChatMessage("key", "   ", signedIn, http).error!!.code)
        assertEquals("invalid_chat_message", sendChatMessage("key", "x".repeat(201), signedIn, http).error!!.code)
        assertEquals("sign_in_required", sendChatMessage("key", "hi", RequestContext(), http).error!!.code)
    }
}
