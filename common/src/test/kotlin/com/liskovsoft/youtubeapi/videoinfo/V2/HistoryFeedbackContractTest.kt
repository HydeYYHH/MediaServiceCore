package com.liskovsoft.youtubeapi.videoinfo.V2

import org.junit.Assert.*
import org.junit.Test

class HistoryFeedbackContractTest {
    private val context = RequestContext(accountId = "owner", webSession = WebSession("SAPISID=fixture", 1), onBehalfOfUser = "brand")
    private fun button(command: String, token: String, icon: String = when (command) {
        "PAUSE" -> "PAUSE_OUTLINED"; "RESUME" -> "PLAY_OUTLINED"; else -> "DELETE"
    }, confirmButtonOnly: Boolean = false): String {
        val feedback = """{"feedbackEndpoint":{"feedbackToken":"$token","cpn":"issued-cpn",
            "extraFeedbackTokens":["extra-$token"],"isFeedbackTokenUnencrypted":true,
            "actions":[{"localWatchHistoryCommand":{"localWatchHistoryCommandType":"LOCAL_WATCH_HISTORY_COMMAND_TYPE_$command"}}]}}"""
        val confirm = if (confirmButtonOnly) """"confirmButton":{"buttonRenderer":{"serviceEndpoint":$feedback}}"""
            else """"confirmEndpoint":$feedback"""
        return """{"buttonRenderer":{"icon":{"iconType":"$icon"},"navigationEndpoint":{"confirmDialogEndpoint":{
            "content":{"confirmDialogRenderer":{$confirm}}}}}}"""
    }
    private fun panel(vararg buttons: String) = """{"contents":{"twoColumnBrowseResultsRenderer":{
        "secondaryContents":{"browseFeedActionsRenderer":{"contents":[${buttons.joinToString(",")}]}}}}}"""

    @Test fun freshHistoryControlsSupplyTheTokensAndFeedbackMustAcknowledgeProcessing() {
        var paused = false
        var reads = 0
        var writes = 0
        var latest = ""
        val observedTokens = ArrayList<String>()
        val http = object : HttpClient {
            override fun execute(request: HttpRequest): HttpReply {
                assertEquals("SAPISID=fixture", request.headers["Cookie"])
                assertEquals("1", request.headers["X-Goog-AuthUser"])
                assertEquals("brand", request.headers["X-Goog-PageId"])
                val root = jsonObject(request.body)!!
                assertEquals("WEB", root.path("context", "client")!!.text("clientName"))
                if (request.url.substringBefore('?') == YOUTUBE_API + "browse") {
                    assertEquals("FEhistory", root.text("browseId"))
                    reads++
                    val command = if (paused) "RESUME" else "PAUSE"
                    latest = "$command-$reads"
                    return HttpReply(200, panel(button(command, latest), button("CLEAR", "CLEAR-$reads", confirmButtonOnly = true)))
                }
                assertEquals(YOUTUBE_API + "feedback", request.url)
                writes++
                val tokens = root.arr("feedbackTokens").map { (it as Json.Str).value }
                val token = tokens.first()
                observedTokens += token
                assertTrue(token == latest || token == "CLEAR-$reads")
                assertEquals(listOf(token, "extra-$token"), tokens)
                assertEquals(true, root.bool("shouldMerge"))
                assertEquals(true, root.bool("isFeedbackTokenUnencrypted"))
                assertEquals("issued-cpn", root.obj("feedbackContext")!!.text("cpn"))
                assertTrue(root.arr("actions").isEmpty())
                paused = when { token.startsWith("PAUSE-") -> true; token.startsWith("RESUME-") -> false; else -> paused }
                return HttpReply(200, """{"feedbackResponses":[{"isProcessed":true}]}""")
            }
        }
        assertEquals(false, loadWatchHistoryManagement(context, http).value!!.paused)
        val pausedReply = setWatchHistory(false, context, http).value!!
        assertTrue(pausedReply.confirmed); assertEquals("feedbackResponses", pausedReply.responseShape)
        assertEquals("feedback_processed", pausedReply.metadata["confirmation"])
        assertTrue(paused)
        assertTrue(setWatchHistory(true, context, http).value!!.confirmed)
        assertFalse(paused)
        assertTrue(clearWatchHistory(context, http).value!!.confirmed)
        assertEquals(listOf("PAUSE-2", "RESUME-3", "CLEAR-4"), observedTokens)
        assertEquals(4, reads); assertEquals(3, writes)
    }

    @Test fun labelsItemMenusDisabledButtonsAndConflictingCommandsCannotInventCapabilities() {
        val pause = button("PAUSE", "token")
        val invalid = listOf("{}", """{"contents":[$pause]}""",
            panel(pause.replace("PAUSE_OUTLINED", "PLAY_OUTLINED")),
            panel(pause.replace("\"buttonRenderer\":{", "\"buttonRenderer\":{\"isDisabled\":true,")),
            panel(pause.replace("\"feedbackToken\":\"token\"", "\"feedbackToken\":\"\"")),
            panel(button("PAUSE", "first"), button("PAUSE", "second")),
            panel(button("PAUSE", "pause"), button("RESUME", "resume")))
        for (body in invalid) {
            var requests = 0
            val http = object : HttpClient {
                override fun execute(request: HttpRequest): HttpReply {
                    requests++
                    assertEquals(YOUTUBE_API + "browse", request.url.substringBefore('?'))
                    return HttpReply(200, body)
                }
            }
            assertNull(watchHistoryManagement(body).paused)
            assertEquals(RequestFailure.UNSUPPORTED, setWatchHistory(false, context, http).error?.kind)
            assertEquals(1, requests)
        }
    }

    @Test fun aSuccessfulHttpStatusOrGenericActionStatusDoesNotProveHistoryFeedbackWasProcessed() {
        for (reply in listOf("{}", """{"status":"STATUS_SUCCEEDED"}""", """{"feedbackResponses":[{"isProcessed":false}]}""")) {
            var requests = 0
            val http = object : HttpClient {
                override fun execute(request: HttpRequest): HttpReply {
                    requests++
                    return HttpReply(200, if (request.url.substringBefore('?').endsWith("/browse")) panel(button("PAUSE", "issued")) else reply)
                }
            }
            assertEquals("feedback_not_processed", setWatchHistory(false, context, http).error?.code)
            assertEquals(2, requests)
            assertEquals("feedback_not_processed", removeWatchHistory("remove-token", context, http).error?.code)
            assertEquals(3, requests)
        }
    }
}
