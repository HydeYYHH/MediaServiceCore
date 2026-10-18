package com.liskovsoft.youtubeapi.videoinfo.V2
import org.junit.Assert.*
import org.junit.Test

class JsonReadTest {
    @Test fun legacyRequestBuildersEscapeEveryControlCharacter() {
        val value="line\u0000\b\n\r\t\u001f"
        assertEquals(value,jsonObject(createCommentQuery(value,"key",null).body)!!.text("commentText"))
        assertEquals(value,jsonObject(commentQuery(null,value,null).body)!!.text("continuation"))
        createPlaylist(value,null,null) { query ->
            assertEquals(value,jsonObject(query.body)!!.text("title"));200 to ""
        }
    }
    @Test fun rejectsMalformedDocuments() {
        for (text in listOf("{\"a\":\"\\uZZZZ\"}", "{\"a\":", "{}", "[01]", "[-]", "[1.]", "[1e]", "{}x", "[1 2]", "{\"a\":\"\n\"}").filter { it != "{}" }) {
            assertNull(text, jsonValue(text))
            assertEquals(emptyList<Comment>(), commentPage(text).comments)
        }
    }
    @Test fun readsNumbersWhitespaceAndUnicode() {
        val root = jsonObject(" { \"n\": -12, \"s\": \"\\u4f60\\u597d\", \"v\": [true, null, 1.25e-2] } ")!!
        assertEquals(-12L, root.long("n"))
        assertEquals("你好", root.text("s"))
        assertEquals(3, root.arr("v").size)
        assertNotNull(jsonValue("[".repeat(128) + "]".repeat(128)))
        assertNull(jsonValue("[".repeat(129) + "]".repeat(129)))
    }
    @Test fun quotesAllJsonControlCharacters() {
        val value = "a\u0000\b\n\t\\\"你好"
        assertEquals(value, (jsonValue(jsonQuote(value)) as Json.Str).value)
    }
}
