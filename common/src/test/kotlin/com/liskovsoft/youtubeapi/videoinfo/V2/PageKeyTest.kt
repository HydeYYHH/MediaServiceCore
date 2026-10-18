package com.liskovsoft.youtubeapi.videoinfo.V2

import org.junit.Assert.*
import org.junit.Test

class PageKeyTest {
    private fun continuation(key: String) = "{\"continuationItemRenderer\":{\"continuationEndpoint\":{\"continuationCommand\":{\"token\":\"$key\"}}}}"

    @Test fun ignoresMenuAndSidebarContinuations() {
        val body = "{\"header\":${continuation("sort")},\"secondaryContents\":${continuation("sidebar")},\"contents\":[${continuation("page")}] }"
        assertEquals("page", searchPage(body).nextKey)
        assertNull(searchPage("{\"menu\":${continuation("sort")}}").nextKey)
    }

    @Test fun keepsGroupAndPageContinuationsInTheirOwnScope() {
        val groups = "{\"shelfRenderer\":{\"items\":[${continuation("first")}] }},{\"shelfRenderer\":{\"items\":[${continuation("second")}]}}"
        assertNull(pageKey(jsonObject("{\"contents\":[$groups]}")!!))
        assertEquals("outer", pageKey(jsonObject("{\"contents\":[$groups,${continuation("outer")}]}")!!))
        assertEquals("first", pageKey(jsonObject("{\"contents\":[{\"shelfRenderer\":{\"items\":[${continuation("first")}]}}]}")!!))
    }
}
