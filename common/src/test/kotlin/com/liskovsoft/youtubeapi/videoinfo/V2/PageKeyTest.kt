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
        assertNull(pageKey(jsonObject("{\"contents\":[{\"shelfRenderer\":{\"items\":[${continuation("first")}]}}]}")!!))
    }

    @Test fun repeatedGroupTokensNeverBecomeAPageTokenByDeduplication() {
        for (kind in listOf("shelfRenderer", "richShelfRenderer")) {
            val group = "{\"$kind\":{\"contents\":[${continuation("same")}]}}"
            assertNull(pageKey(jsonObject("{\"contents\":[$group,$group]}")!!))
            assertEquals("same", pageKey(jsonObject("{\"contents\":[$group,${continuation("same")}]}")!!))
        }
    }

    @Test fun primaryItemSectionsKeepTheirOwnOuterContinuationWithoutBorrowingAShelf() {
        val primary = "{\"itemSectionRenderer\":{\"contents\":[${continuation("primary")}]}}"
        assertEquals("primary", pageKey(jsonObject("{\"contents\":[$primary]}")!!))
        assertNull(pageKey(jsonObject("{\"contents\":[$primary,$primary]}")!!))
        assertNull(pageKey(jsonObject("{\"contents\":[${continuation("first")},${continuation("second")}]}")!!))
    }

    @Test fun aCommentsReloadTokenRemainsLocalToItsPrimarySection() {
        val section = """{"itemSectionRenderer":{"continuations":[{"reloadContinuationData":{"continuation":"comments"}}]}}"""
        assertEquals("comments", pageKey(jsonObject("{\"contents\":[$section]}")!!))
        assertNull(pageKey(jsonObject("{\"contents\":[$section]}")!!, forwardOnly = true))
        val shelf = "{\"shelfRenderer\":{\"content\":{\"contents\":[$section]}}}"
        assertNull(pageKey(jsonObject(shelf)!!))
    }

    @Test fun anAmbiguousPrimarySectionCannotDisappearBeforeASiblingIsChosen() {
        fun section(items: String) = "{\"itemSectionRenderer\":{\"contents\":[$items]}}"
        for (second in listOf("first", "second")) {
            val ambiguous = section(continuation("first") + "," + continuation(second))
            val sibling = section(continuation("sibling"))
            for (first in listOf(ambiguous, section(ambiguous))) {
                assertNull(pageKey(jsonObject("{\"contents\":[$first,$sibling]}")!!))
                assertEquals("outer", pageKey(jsonObject("{\"contents\":[$first,$sibling,${continuation("outer")}]}")!!))
            }
        }
    }
}
