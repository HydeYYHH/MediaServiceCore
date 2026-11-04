package com.liskovsoft.youtubeapi.videoinfo.V2
import org.junit.Assert.*
import org.junit.Test
class PageActionTest {
    @Test fun aMenuButtonKeepsTheActionsInsideItsPopup() {
        val row = jsonObject("""{"buttonRenderer":{"serviceEndpoint":{"openPopupAction":{"popup":{
            "menuRenderer":{"items":[{"menuServiceItemRenderer":{"serviceEndpoint":{"feedbackEndpoint":{
            "feedbackToken":"nested-token","uiActions":{"hideEnclosingContainer":true}}}}}]}}}}}}""")!!
        val action = pageActions(row).single { it.kind == MenuActionKind.NotInterested }
        assertEquals("nested-token", action.kindToken)
    }

    @Test fun classifiesTheChannelConfirmationWithoutUsingItsLabels() {
        val row = channelConfirmation()
        val action = pageActions(jsonObject(row)!!).single()
        assertEquals(MenuActionKind.DontRecommendChannel, action.kind)
        assertEquals("channel-token", action.kindToken)
        assertEquals("openPopupAction", action.endpoint.name)
        val translated = row.replace("placeholder", "localized")
        assertEquals(action.kind, pageActions(jsonObject(translated)!!).single().kind)
    }

    @Test fun anIncompleteConfirmationDoesNotExposeAFeedbackAction() {
        for (row in listOf(
            channelConfirmation().replace("\"hideEnclosingContainer\":true", "\"hideEnclosingContainer\":false"),
            channelConfirmation().replace("\"childId\":\"container\"", "\"childId\":\"\""),
            channelConfirmation().replace("\"feedbackToken\":\"channel-token\"", "\"feedbackToken\":\"\""),
            channelConfirmation().replace("\"signalAction\":{}", "\"unknownAction\":{}"),
        )) assertNull(pageActions(jsonObject(row)!!).single().kind)
    }

    private fun channelConfirmation() = """{"menuServiceItemRenderer":{"text":{"simpleText":"placeholder"},
        "command":{"openPopupAction":{"popup":{"overlaySectionRenderer":{"overlay":{"overlayTwoPanelRenderer":{
        "actionPanel":{"overlayPanelRenderer":{"content":{"overlayPanelItemListRenderer":{"items":[
        {"compactLinkRenderer":{"title":{"simpleText":"placeholder"},"serviceEndpoint":{"commandExecutorCommand":{
        "commands":[{"feedbackEndpoint":{"feedbackToken":"channel-token","uiActions":{"hideEnclosingContainer":true},
        "actions":[{"removeItemAction":{"childId":"container"}}]}},{"signalAction":{}}]}}}},
        {"compactLinkRenderer":{"title":{"simpleText":"placeholder"},"serviceEndpoint":{"signalAction":{}}}}
        ]}}}}}}}}}}}}"""

    @Test fun containerDismissalRequiresAnEnabledFlag() {
        fun row(enabled: Boolean) = jsonObject("""{"menuServiceItemRenderer":{"serviceEndpoint":{
            "feedbackEndpoint":{"feedbackToken":"video-token","uiActions":{"hideEnclosingContainer":$enabled}}}}}""")!!
        assertEquals(MenuActionKind.NotInterested, pageActions(row(true)).single().kind)
        assertEquals("video-token", pageActions(row(true)).single().kindToken)
        assertNull(pageActions(row(false)).single().kind)
    }

    @Test fun aConfirmationCommandDoesNotBecomeAnImmediateCardDismissal() {
        val row = jsonObject("""{"menuServiceItemRenderer":{"command":{"openPopupAction":{"popup":{
            "overlayPanelItemListRenderer":{"items":[{"compactLinkRenderer":{"serviceEndpoint":{
                "feedbackEndpoint":{"feedbackToken":"confirmed-token","uiActions":{"hideEnclosingContainer":true}}}}}]}}}}}}""")!!
        val action = pageActions(row).single()
        assertEquals("openPopupAction", action.endpoint.name)
        assertNull(action.kind)
    }

    @Test fun readsSingleTokensAndCommandExecutorMenus() {
        assertEquals("single", PageAction("Not interested",
            ActionEndpoint("feedbackEndpoint", """{"feedbackToken":"single"}""")).feedbackToken())
        assertEquals("nested", PageAction("Don't recommend channel",
            ActionEndpoint("commandExecutorCommand", """{"commands":[{"feedbackEndpoint":{"feedbackToken":"nested"}}]}""")).feedbackToken())
    }
    @Test fun extractsTheFeedbackTokenFromHistoryItemMenus() {
        val action = PageAction("Remove from watch history",
            ActionEndpoint("feedbackEndpoint", """{"feedbackTokens":["token-one"]}"""))
        assertEquals("token-one", action.feedbackToken())
    }
    @Test fun findsTokensNestedDeeperInThePayload() {
        val action = PageAction(null,
            ActionEndpoint("feedbackEndpoint", """{"undoFeedbackEndpoint":{"feedbackTokens":[" ","token-two"]}}"""))
        assertEquals("token-two", action.feedbackToken())
    }
    @Test fun ignoresOtherEndpointsAndBrokenPayloads() {
        assertNull(PageAction("Share", ActionEndpoint("shareEntityServiceEndpoint", """{"serializedShareEntity":"x"}""")).feedbackToken())
        assertNull(PageAction(null, ActionEndpoint("feedbackEndpoint", "not json")).feedbackToken())
        assertNull(PageAction(null, ActionEndpoint("feedbackEndpoint", """{"feedbackTokens":[]}""")).feedbackToken())
    }
    @Test fun classifiesFeedbackKindsFromTheUiActionsShape() {
        val menu = """{"menuServiceItemRenderer":{"serviceEndpoint":{"feedbackEndpoint":{
            "feedbackToken":"video-token","uiActions":{"hideUnhideUis":{"hiddenUiIds":["a"]}}}}}}"""
        val hide = pageActions(jsonObject(menu)!!).single()
        assertEquals(MenuActionKind.NotInterested, hide.kind)
        assertEquals("video-token", hide.kindToken)
        // A localized (non-English) title must not change the classification.
        val localizedMenu = """{"menuServiceItemRenderer":{"text":{"simpleText":"不感兴趣"},
            "serviceEndpoint":{"feedbackEndpoint":{"feedbackToken":"video-token",
            "uiActions":{"hideUnhideUis":{"hiddenUiIds":["a"]}}}}}}"""
        assertEquals(MenuActionKind.NotInterested, pageActions(jsonObject(localizedMenu)!!).single().kind)
        val channel = """{"menuServiceItemRenderer":{"serviceEndpoint":{"feedbackEndpoint":{
            "feedbackToken":"channel-token","uiActions":{"hideEnclosingContainerUis":{"containerUiIds":["b"]}}}}}}"""
        val dontRecommend = pageActions(jsonObject(channel)!!).single()
        assertEquals(MenuActionKind.DontRecommendChannel, dontRecommend.kind)
        assertEquals("channel-token", dontRecommend.kindToken)
    }
    @Test fun classifiesNotInterestedFromTheItemIconWhenNoUiActionsRender() {
        val menu = """{"menuServiceItemRenderer":{"icon":{"iconType":"NOT_INTERESTED"},
            "serviceEndpoint":{"feedbackEndpoint":{"feedbackToken":"icon-token"}}}}"""
        val action = pageActions(jsonObject(menu)!!).single()
        assertEquals(MenuActionKind.NotInterested, action.kind)
        assertEquals("icon-token", action.kindToken)
        // A token alone does not identify the operation.
        val plain = """{"menuServiceItemRenderer":{"serviceEndpoint":{"feedbackEndpoint":{"feedbackToken":"x"}}}}"""
        assertNull(pageActions(jsonObject(plain)!!).single().kind)
        // With more than one feedback site the shape is a panel, not a card dismissal.
        val panel = """{"menuServiceItemRenderer":{"serviceEndpoint":{"commandExecutorCommand":{"commands":[
            {"feedbackEndpoint":{"feedbackToken":"a"}},{"feedbackEndpoint":{"feedbackToken":"b"}}]}}}}"""
        assertNull(pageActions(jsonObject(panel)!!).firstOrNull()?.kind)
    }
    @Test fun historyRemovalDoesNotBecomeRecommendationFeedback() {
        val menu = """{"menuServiceItemRenderer":{"icon":{"iconType":"HISTORY"},
            "serviceEndpoint":{"feedbackEndpoint":{"feedbackToken":"history-token"}}}}"""
        val action = pageActions(jsonObject(menu)!!).single()
        assertNull(action.kind)
        assertNull(action.kindToken)
        assertEquals("history-token", action.feedbackToken())
    }
    @Test fun classifiesFeedbackInsideCommandExecutorMenus() {
        val menu = """{"menuServiceItemRenderer":{"serviceEndpoint":{"commandExecutorCommand":{"commands":[
            {"feedbackEndpoint":{"feedbackToken":"nested","uiActions":{"hideEnclosingContainerUis":{}}}}]}}}}"""
        val action = pageActions(jsonObject(menu)!!).single()
        assertEquals(MenuActionKind.DontRecommendChannel, action.kind)
        assertEquals("nested", action.kindToken)
    }
    @Test fun bothFeedbackMenuItemsKeepTheirOwnTokens() {
        val menu = """{"menu":[
            {"menuServiceItemRenderer":{"serviceEndpoint":{"feedbackEndpoint":{"feedbackToken":"video",
             "uiActions":{"hideUnhideUis":{}}}}}},
            {"menuServiceItemRenderer":{"serviceEndpoint":{"feedbackEndpoint":{"feedbackToken":"channel",
             "uiActions":{"hideEnclosingContainerUis":{}}}}}}]}"""
        val actions = pageActions(jsonObject(menu)!!)
        assertEquals(2, actions.size)
        assertEquals("video", actions.first { it.kind == MenuActionKind.NotInterested }.kindToken)
        assertEquals("channel", actions.first { it.kind == MenuActionKind.DontRecommendChannel }.kindToken)
    }

    @Test fun censusShapedRowsStayUnclassifiedWithoutSemanticShape() {
        // Desensitized census shape (subscriptions rows): a localized menu row whose
        // feedback endpoint carries one shapeless token — no uiActions, no icon.
        val row = """{"menuServiceItemRenderer":{"text":{"simpleText":"不限"},
            "serviceEndpoint":{"feedbackEndpoint":{"feedbackToken":"census-token"}}}}"""
        val action = pageActions(jsonObject(row)!!).single()
        assertNull(action.kind)
        assertNull(action.kindToken)
        // Ad-privacy opt-in/out buttons carry feedback tokens without card semantics.
        val adDialog = """{"buttonViewModel":{"title":"optIn",
            "onTap":{"innertubeCommand":{"feedbackEndpoint":{"feedbackToken":"ad-token"}}}}}"""
        assertNull(pageActions(jsonObject(adDialog)!!).single().kind)
        // The same row met twice (card command and long-press menu) collapses to one action.
        val duplicated = """{"menu":[{"menuServiceItemRenderer":{"text":{"simpleText":"不限"},
            "serviceEndpoint":{"feedbackEndpoint":{"feedbackToken":"census-token"}}}},
            {"menuServiceItemRenderer":{"text":{"simpleText":"不限"},
            "serviceEndpoint":{"feedbackEndpoint":{"feedbackToken":"census-token"}}}}]}"""
        assertEquals(1, pageActions(jsonObject(duplicated)!!).size)
    }
}
