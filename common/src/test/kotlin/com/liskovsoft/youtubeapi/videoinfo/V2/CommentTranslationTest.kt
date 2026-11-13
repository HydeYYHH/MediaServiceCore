package com.liskovsoft.youtubeapi.videoinfo.V2

import java.net.URLDecoder
import java.util.Base64
import org.junit.Assert.*
import org.junit.Test

class CommentTranslationTest {
    private val translated = """{"frameworkUpdates":{"entityBatchUpdate":{"mutations":[{"payload":{
        "commentEntityPayload":{"translatedContent":{"content":"Translated comment"}}
    }}]}}}"""

    @Test fun translationUsesTheYouTubeCommentActionAndReturnsOnlyServerContent() {
        var calls = 0
        val text = "Original text " + "long comment ".repeat(40) + "🙂"
        val result = translateComment(text, "zh-Hant", http = object : HttpClient {
            override fun execute(request: HttpRequest): HttpReply {
                calls++
                assertEquals(YOUTUBE_API + "comment/perform_comment_action", request.url)
                val root = jsonObject(request.body)!!
                assertFalse(root.fields.containsKey("action"))
                val action = (root.arr("actions").single() as Json.Str).value
                val decoded = Base64.getUrlDecoder().decode(URLDecoder.decode(action, "UTF-8"))
                assertEquals(8, decoded[0].toInt()); assertEquals(22, decoded[1].toInt())
                val strings = decoded.toString(Charsets.UTF_8)
                assertTrue(strings.contains("Original text")); assertTrue(strings.contains("zh-Hant"))
                assertFalse(strings.contains("🙂"))
                return HttpReply(200, translated)
            }
        })
        assertTrue(result.ok)
        assertEquals(CommentTranslation("Translated comment", "zh-Hant"), result.value)
        assertEquals(1, calls)
    }

    @Test fun translationActionUsesTheUrlSafeAlphabetRequiredByTheEndpoint() {
        val result = translateComment("Hello, how are you today?", "zh-CN", http = object : HttpClient {
            override fun execute(request: HttpRequest): HttpReply {
                val action = (jsonObject(request.body)!!.arr("actions").single() as Json.Str).value
                val encoded = URLDecoder.decode(action, "UTF-8")
                assertTrue(encoded.contains('_'))
                assertFalse(encoded.contains('/'))
                assertFalse(encoded.contains('+'))
                val wire = Base64.getUrlDecoder().decode(encoded)
                // Complete protobuf wire fixture, including multibyte tags 23/31 and nested text.
                val expected = "081610021a01202a0120ba010120fa01291201201a1d0a1b0a1948656c6c6f2c20686f772061726520796f7520746f6461793f22057a682d434e"
                assertEquals(expected, wire.joinToString("") { "%02x".format(it.toInt() and 255) })
                return HttpReply(200, translated)
            }
        })
        assertTrue(result.ok)
    }

    @Test fun malformedOrUnrelatedRepliesNeverPretendToBeTranslated() {
        for (body in listOf("{}", """{"translatedContent":{"content":"Unrelated"}}""",
            translated.replace("Translated comment", ""))) {
            val result = translateComment("Original", "en", http = object : HttpClient {
                override fun execute(request: HttpRequest) = HttpReply(200, body)
            })
            assertEquals("unknown_comment_translation", result.error?.code)
            assertNull(result.value)
        }
    }

    @Test fun invalidInputDoesNotIssueARequest() {
        val http = object : HttpClient { override fun execute(request: HttpRequest): HttpReply = error("No network") }
        assertFalse(translateComment("", "en", http = http).ok)
        assertFalse(translateComment("Original", "en\r\n", http = http).ok)
        assertFalse(translateComment("🙂", "en", http = http).ok)
    }
}
