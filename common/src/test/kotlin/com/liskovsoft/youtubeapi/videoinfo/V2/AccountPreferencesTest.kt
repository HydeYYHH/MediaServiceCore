package com.liskovsoft.youtubeapi.videoinfo.V2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountPreferencesTest {
    @Test fun selectingServerLanguageUsesItsStringValueAndCannotUseAnotherSessionsChoice() {
        val account = AccountSession(RequestContext(accountId = "fixture", webSession = WebSession("SAPISID=fixture")))
        val session = AccountPreferencesSession(account)
        val server = LanguageServer()
        val loaded = session.load(AccountPreferencesSection.NOTIFICATIONS, server)
        assertTrue(loaded.error?.code.orEmpty(), loaded.ok)
        val language = loaded.value!!.preferences.single() as AccountPreferenceSelection
        val spanish = language.choices.single { it.label == "Español" }

        val replacement = AccountPreferencesSession(account)
        val refused = replacement.select(language, spanish.key, server)
        assertEquals("account_preference_session_changed", refused.error!!.code)
        assertEquals(0, server.writes)
        assertEquals(1, server.reads)

        val selected = session.select(language, spanish.key, server)
        assertTrue(selected.error?.code.orEmpty(), selected.ok)
        assertEquals("es", server.language)
        assertEquals("opaque-language-item", server.lastItem)
        assertEquals("email-language", server.lastClientItem)
        assertEquals(1, server.writes)
        assertEquals(2, server.reads)
        val readback = selected.value!!.preferences.single() as AccountPreferenceSelection
        assertEquals("Español", readback.choices.single { it.selected }.label)
    }

    @Test fun inverseWireValuesNeedServerReadbackAndRetiredCapabilitiesCannotWrite() {
        val account = AccountSession(RequestContext(accountId = "fixture", webSession = WebSession("SAPISID=fixture")))
        val settings = AccountPreferencesSession(account)
        val server = PreferenceServer()
        val loaded = settings.load(AccountPreferencesSection.PRIVACY, server)
        assertTrue(loaded.error?.code.orEmpty(), loaded.ok)
        val original = loaded.value!!.preferences.single() as AccountPreferenceToggle
        val updated = settings.set(original, false, server)
        assertTrue(updated.ok)
        assertFalse((updated.value!!.preferences.single() as AccountPreferenceToggle).enabled)
        assertFalse(server.enabled)
        assertEquals(1, server.writes)
        assertEquals(2, server.reads)

        server.ignoreWrites = true
        val retained = updated.value!!.preferences.single() as AccountPreferenceToggle
        val refused = settings.set(retained, true, server)
        assertEquals("account_preference_unconfirmed", refused.error!!.code)
        assertFalse(server.enabled)
        assertEquals(2, server.writes)
        assertEquals(3, server.reads)

        account.invalidate()
        assertEquals("account_preference_session_changed", settings.set(retained, true, server).error!!.code)
        assertEquals(2, server.writes)
        assertEquals(3, server.reads)
    }

    private class PreferenceServer : HttpClient {
        var enabled = true
        var ignoreWrites = false
        var reads = 0
        var writes = 0
        override fun execute(request: HttpRequest): HttpReply {
            if (request.url.endsWith("account/set_setting")) {
                writes++
                if (!ignoreWrites) enabled = !jsonObject(request.body)!!.bool("boolValue")!!
                return HttpReply(200, """{"status":"SUCCESS"}""")
            }
            reads++
            fun endpoint(value: Boolean) = """{"commandMetadata":{"webCommandMetadata":{"apiUrl":"/youtubei/v1/account/set_setting"}},"setSettingEndpoint":{"settingItemId":"wire-key","settingItemIdForClient":"client-key","boolValue":$value}}"""
            return HttpReply(200, """{"contents":{"settingsOptionsRenderer":{"title":{"simpleText":"Privacy"},"options":[{"settingsSwitchRenderer":{"title":{"simpleText":"Keep subscriptions private"},"enabled":$enabled,"enableServiceEndpoint":${endpoint(false)},"disableServiceEndpoint":${endpoint(true)}}}]}}}""")
        }
    }

    private class LanguageServer : HttpClient {
        var language = "en"
        var writes = 0
        var reads = 0
        var lastItem: String? = null
        var lastClientItem: String? = null
        override fun execute(request: HttpRequest): HttpReply {
            if (request.url.endsWith("account/set_setting")) {
                writes++
                val body = jsonObject(request.body)!!
                lastItem = body.text("settingItemId")
                lastClientItem = body.text("settingItemIdForClient")
                assertEquals(null, body.fields["boolValue"])
                language = requireNotNull(body.text("stringValue"))
                return HttpReply(200, """{"status":"SUCCESS"}""")
            }
            reads++
            fun choice(value: String, label: String) = """{"dropdownItemRenderer":{"label":{"simpleText":"$label"},"isSelected":${value == language},"onSelectCommand":{"commandMetadata":{"webCommandMetadata":{"apiUrl":"/youtubei/v1/account/set_setting"}},"setSettingEndpoint":{"settingItemId":"opaque-language-item","settingItemIdForClient":"email-language","stringValue":"$value"}}}}"""
            return HttpReply(200, """{"contents":{"settingsOptionsRenderer":{"options":[{"dropdownRenderer":{"label":{"simpleText":"Email language"},"entries":[${choice("en", "English")},${choice("es", "Español")}]}}]}}}""")
        }
    }
}
