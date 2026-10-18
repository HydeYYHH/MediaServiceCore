package com.liskovsoft.youtubeapi.videoinfo.V2
import org.junit.Assert.*
import org.junit.Test
class DeArrowReadTest {
    @Test fun preservesRankingButOnlySelectsTrustedCandidates() {
        val data=deArrowData("""{"titles":[{"title":"Unconfirmed","votes":-1},{"title":"Good","votes":1}],"thumbnails":[{"timestamp":12.5,"votes":0}],"videoDuration":100,"randomTime":0.2}""")!!
        assertEquals("Unconfirmed",data.titles.first().title);assertEquals("Good",data.preferredTitle()!!.title)
        assertEquals("12.5",urlParameter(deArrowThumbnailUrl("v",data)!!,"time"))
        assertNull(deArrowThumbnailUrl("v",DeArrowData(emptyList(),emptyList(),null,0.2)))
    }
}
