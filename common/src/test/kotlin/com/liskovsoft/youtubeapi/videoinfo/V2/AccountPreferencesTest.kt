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
        assertEquals(null, server.lastClientItem)
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
        val otherSession = AccountPreferencesSession(account)
        assertEquals("account_preference_session_changed", otherSession.set(original, false, server).error!!.code)
        assertEquals(0, server.writes)
        assertEquals(1, server.reads)
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
        assertEquals(5, server.reads)

        account.invalidate()
        assertEquals("account_preference_session_changed", settings.set(retained, true, server).error!!.code)
        assertEquals(2, server.writes)
        assertEquals(5, server.reads)
    }

    @Test fun aDelayedReadbackConfirmsWithoutSendingTheMutationAgain() {
        val account = AccountSession(RequestContext(accountId = "fixture", webSession = WebSession("SAPISID=fixture")))
        val settings = AccountPreferencesSession(account)
        val server = PreferenceServer()
        val original = settings.load(AccountPreferencesSection.PRIVACY, server).value!!.preferences.single() as AccountPreferenceToggle
        server.staleReadbacks = 1

        val result = settings.set(original, false, server)

        assertTrue(result.error?.code.orEmpty(), result.ok)
        assertFalse((result.value!!.preferences.single() as AccountPreferenceToggle).enabled)
        assertEquals(1, server.writes)
        assertEquals(3, server.reads)
    }

    @Test fun aRejectedMutationNeverStartsReadbackOrResends() {
        val account = AccountSession(RequestContext(accountId = "fixture", webSession = WebSession("SAPISID=fixture")))
        val settings = AccountPreferencesSession(account)
        val server = PreferenceServer()
        val original = settings.load(AccountPreferencesSection.PRIVACY, server).value!!.preferences.single() as AccountPreferenceToggle
        server.writeStatus = 400

        val result = settings.set(original, false, server)

        assertEquals(400, result.error!!.status)
        assertEquals(1, server.writes)
        assertEquals(1, server.reads)
        assertTrue(server.enabled)
    }

    @Test fun aReadbackErrorStopsBeforeAnotherReadOrMutation() {
        val account = AccountSession(RequestContext(accountId = "fixture", webSession = WebSession("SAPISID=fixture")))
        val settings = AccountPreferencesSession(account)
        val server = PreferenceServer()
        val original = settings.load(AccountPreferencesSection.PRIVACY, server).value!!.preferences.single() as AccountPreferenceToggle
        server.failReadback = true

        val result = settings.set(original, false, server)

        assertEquals(503, result.error!!.status)
        assertEquals(1, server.writes)
        assertEquals(2, server.reads)
    }

    @Test fun anIdentityChangeDuringTheFirstReadbackStopsImmediately() {
        val account = AccountSession(RequestContext(accountId = "fixture", webSession = WebSession("SAPISID=fixture")))
        val settings = AccountPreferencesSession(account)
        val server = PreferenceServer()
        val original = settings.load(AccountPreferencesSection.PRIVACY, server).value!!.preferences.single() as AccountPreferenceToggle
        server.staleReadbacks = 1
        server.afterRead = { account.invalidate() }

        val result = settings.set(original, false, server)

        assertEquals("account_preference_session_changed", result.error!!.code)
        assertEquals(1, server.writes)
        assertEquals(2, server.reads)
    }

    @Test fun integerChoicesUseTheSameNewValueEnvelopeWithoutClientOnlyFields() {
        val account = AccountSession(RequestContext(accountId = "fixture", webSession = WebSession("SAPISID=fixture")))
        val settings = AccountPreferencesSession(account)
        val server = LanguageServer(integerValues = true)
        val selection = settings.load(AccountPreferencesSection.NOTIFICATIONS, server).value!!.preferences.single() as AccountPreferenceSelection
        val spanish = selection.choices.single { it.label == "Español" }

        val result = settings.select(selection, spanish.key, server)

        assertTrue(result.error?.code.orEmpty(), result.ok)
        assertEquals("es", server.language)
        assertEquals("Español", (result.value!!.preferences.single() as AccountPreferenceSelection).choices.single { it.selected }.label)
        assertEquals(1, server.writes)
        assertEquals(2, server.reads)
    }

    private class PreferenceServer : HttpClient {
        var enabled = true
        var ignoreWrites = false
        var staleReadbacks = 0
        var writeStatus = 200
        var failReadback = false
        var afterRead: (() -> Unit)? = null
        private var pendingEnabled: Boolean? = null
        var reads = 0
        var writes = 0
        override fun execute(request: HttpRequest): HttpReply {
            if (request.url.endsWith("account/set_setting")) {
                writes++
                val body = jsonObject(request.body)!!
                val value = body.obj("newValue")
                if (body.fields.keys != setOf("context", "settingItemId", "newValue") ||
                    body.text("settingItemId") != "wire-key" || value?.fields?.keys != setOf("boolValue") ||
                    value?.bool("boolValue") == null) return HttpReply(400, """{"error":{"code":400}}""")
                if (writeStatus != 200) return HttpReply(writeStatus, """{"error":{"code":$writeStatus}}""")
                if (!ignoreWrites) pendingEnabled = !value!!.bool("boolValue")!!
                return HttpReply(200, """{"status":"SUCCESS"}""")
            }
            reads++
            if (failReadback) return HttpReply(503, """{"error":{"code":503}}""")
            pendingEnabled?.let {
                if (staleReadbacks > 0) staleReadbacks-- else {
                    enabled = it
                    pendingEnabled = null
                }
            }
            afterRead?.invoke()
            fun endpoint(value: Boolean) = """{"commandMetadata":{"webCommandMetadata":{"apiUrl":"/youtubei/v1/account/set_setting"}},"setSettingEndpoint":{"settingItemId":"wire-key","settingItemIdForClient":"client-key","boolValue":$value}}"""
            return HttpReply(200, """{"contents":{"settingsOptionsRenderer":{"title":{"simpleText":"Privacy"},"options":[{"settingsSwitchRenderer":{"title":{"simpleText":"Keep subscriptions private"},"enabled":$enabled,"enableServiceEndpoint":${endpoint(false)},"disableServiceEndpoint":${endpoint(true)}}}]}}}""")
        }
    }

    private class LanguageServer(private val integerValues: Boolean = false) : HttpClient {
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
                assertEquals(setOf("context", "settingItemId", "newValue"), body.fields.keys)
                val newValue = requireNotNull(body.obj("newValue"))
                assertEquals(setOf(if (integerValues) "intValue" else "stringValue"), newValue.fields.keys)
                language = if (integerValues) when (newValue.scalar("intValue")) {
                    "104" -> "en"
                    "208" -> "es"
                    else -> error("Unexpected setting enum")
                } else requireNotNull(newValue.text("stringValue"))
                return HttpReply(200, """{"status":"SUCCESS"}""")
            }
            reads++
            fun choice(value: String, label: String): String {
                val valueField = if (integerValues) "\"intValue\":" + (if (value == "en") "104" else "208")
                    else "\"stringValue\":\"$value\""
                return """{"dropdownItemRenderer":{"label":{"simpleText":"$label"},"isSelected":${value == language},"onSelectCommand":{"commandMetadata":{"webCommandMetadata":{"apiUrl":"/youtubei/v1/account/set_setting"}},"setSettingEndpoint":{"settingItemId":"opaque-language-item","settingItemIdForClient":"email-language",$valueField}}}}"""
            }
            return HttpReply(200, """{"contents":{"settingsOptionsRenderer":{"options":[{"dropdownRenderer":{"label":{"simpleText":"Email language"},"entries":[${choice("en", "English")},${choice("es", "Español")}]}}]}}}""")
        }
    }
}
