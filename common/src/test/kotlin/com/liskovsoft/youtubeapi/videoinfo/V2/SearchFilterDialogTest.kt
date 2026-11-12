package com.liskovsoft.youtubeapi.videoinfo.V2

import org.junit.Assert.*
import org.junit.Test

class SearchFilterDialogTest {
    private val group = """{"searchFilterGroupRenderer":{"title":{"simpleText":"Upload date"},"filters":[
        {"searchFilterRenderer":{"label":{"simpleText":"This year"},"navigationEndpoint":{
            "commandMetadata":{"webCommandMetadata":{"apiUrl":"/youtubei/v1/search","sendPost":true}},
            "searchEndpoint":{"query":"lessons","params":"issued-date"}}}},
        {"searchFilterRenderer":{"label":{"simpleText":"Any time"},"status":"FILTER_STATUS_SELECTED"}}
    ]}}"""
    private fun dialog(groups: String = "[$group]", extra: String = "") =
        """{"searchFilterOptionsDialogRenderer":{"title":{"simpleText":"Search filters"},"groups":$groups}$extra}"""
    private fun button(popup: String = dialog()) = """{"buttonRenderer":{"command":{"openPopupAction":{"popup":$popup}}}}"""
    private fun page(control: String = button(), subMenu: Boolean = false): String {
        val submenu = if (subMenu) """, "subMenu":{"searchSubMenuRenderer":{"button":$control}}""" else ""
        val header = if (subMenu) "" else """, "header":{"searchHeaderRenderer":{"searchFilterButton":$control}}"""
        return """{"contents":{"twoColumnSearchResultsRenderer":{"primaryContents":{"sectionListRenderer":{
            "contents":[{"itemSectionRenderer":{"contents":[{"videoRenderer":{"videoId":"own","title":{"simpleText":"Lesson"}}}]}}]$submenu
        }}}}$header}"""
    }

    @Test fun anOwnedWebDialogSuppliesDateCapabilitiesWithoutIssuingItsUiAction() {
        val body = page()
        val loaded = loadSearch("lessons", http = object : HttpClient {
            override fun execute(request: HttpRequest) = HttpReply(200, body)
        }).value!!
        assertEquals(listOf("own"), loaded.items.map { it.id })
        assertEquals(listOf("This year", "Any time"), loaded.filters.map { it.title })
        assertEquals("Upload date", loaded.filters.first().group)
        assertEquals("issued-date", loaded.filters.first().params)
        assertTrue(loaded.filters.last().selected)
        assertNull(loaded.filters.last().params)
        assertTrue(loaded.actions.isEmpty())
        assertEquals(loaded.filters, searchFilters(body))
    }

    @Test fun sectionSubmenuAndItsHeaderReloadKeepTheSameDialogOwnership() {
        val submenu = page(subMenu = true)
        assertEquals(2, searchPage(submenu).filters.size)
        val body = """{"onResponseReceivedCommands":[
            {"appendContinuationItemsAction":{"continuationItems":[{"videoRenderer":{"videoId":"next"}}]}},
            {"reloadContinuationItemsCommand":{"slot":"RELOAD_CONTINUATION_SLOT_HEADER","continuationItems":[
                {"searchSubMenuRenderer":{"button":${button()}}}
            ]}}
        ]}"""
        assertEquals(2, searchPage(body).filters.size)
    }

    @Test fun filterDialogDescendantsOutsideAnOwnedControlCannotSupplyCapabilities() {
        val shadow = page().removeSuffix("}") + """, "topbar":${button()},"engagementPanels":[${dialog()}]}"""
        val noHeader = page().replace("\"searchFilterButton\":${button()}", "\"text\":{\"simpleText\":\"Filters\"}")
        assertEquals(2, searchPage(shadow).filters.size)
        assertTrue(searchPage(noHeader.removeSuffix("}") + """, "topbar":${button()}}""").filters.isEmpty())
    }

    @Test fun ambiguousPopupOwnersAndWrongTypedGroupCollectionsAreRejected() {
        val menu = ",\"menuRenderer\":{\"items\":[$group]}"
        assertTrue(searchPage(page(button(dialog(extra = menu)))).filters.isEmpty())
        for (groups in listOf("null", "{}", "false", "0", "\"groups\"")) {
            assertTrue(searchPage(page(button(dialog(groups)))).filters.isEmpty())
        }
        val competing = group.replace("\"navigationEndpoint\":{", "\"serviceEndpoint\":{},\"navigationEndpoint\":{")
        assertTrue(searchPage(page(button(dialog("[$competing]")))).filters.isEmpty())
    }

    @Test fun foreignRoutesOnTheDialogOrItsGroupCannotSupplySearchCapabilities() {
        val route = "\"commandMetadata\":{\"webCommandMetadata\":{\"apiUrl\":\"/youtubei/v1/browse\"}},"
        val foreignDialog = dialog().replace("\"title\":", route + "\"title\":")
        assertTrue(searchPage(page(button(foreignDialog))).filters.isEmpty())
        val foreignGroup = group.replace("\"title\":", route + "\"title\":")
        assertTrue(searchPage(page(button(dialog("[$foreignGroup]")))).filters.isEmpty())
    }

    @Test fun catalogFilterUsesTheDialogIssuedParamsForItsCumulativeSearch() {
        val requests = mutableListOf<HttpRequest>()
        val http = object : HttpClient {
            override fun execute(request: HttpRequest): HttpReply {
                requests += request
                return HttpReply(200, page())
            }
        }
        val catalog = CatalogSession(AccountSession(), http)
        val initial = catalog.search("lessons").value!!
        val date = initial.filters.single { it.title == "This year" }
        assertTrue(catalog.filter(date).ok)
        assertEquals(2, requests.size)
        assertEquals("issued-date", jsonObject(requests.last().body)!!.text("params"))
    }
}
