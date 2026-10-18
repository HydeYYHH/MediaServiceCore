package com.liskovsoft.youtubeapi.videoinfo.V2
import org.junit.Assert.*
import org.junit.Test
class ChannelBrowseTest {
    @Test fun preservesMetadataAndServerTabParameters() {
        val p = channelPage("""{"metadata":{"channelMetadataRenderer":{"externalId":"UC1","title":"Name","description":"About"}},"tabs":[{"tabRenderer":{"title":"Videos","endpoint":{"browseEndpoint":{"browseId":"UC1","params":"opaque"}}}}]}""")
        assertEquals("Name", p.title); assertEquals("opaque", p.page.tabs.single().params)
        val q = channelQuery("UC1", params = p.page.tabs.single().params, search = "a\"b")
        assertEquals("a\"b", jsonObject(q.body)!!.text("query")); assertEquals("opaque", jsonObject(q.body)!!.text("params"))
        assertEquals("", channelQuery("bad/id").url)
    }
}
