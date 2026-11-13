package com.liskovsoft.youtubeapi.videoinfo.V2

enum class AccountPreferencesSection(val browseId: String) {
    PRIVACY("SPaccount_privacy"), NOTIFICATIONS("SPaccount_notifications")
}

sealed class AccountPreference(val key: String, val title: String, val summary: String)

class AccountPreferenceToggle internal constructor(
    key: String, title: String, summary: String, val enabled: Boolean,
    internal val enable: AccountPreferenceCommand, internal val disable: AccountPreferenceCommand,
) : AccountPreference(key, title, summary)

class AccountPreferenceChoice internal constructor(
    val key: String, val label: String, val selected: Boolean,
    internal val command: AccountPreferenceCommand,
)

class AccountPreferenceSelection internal constructor(
    key: String, title: String, val choices: List<AccountPreferenceChoice>,
) : AccountPreference(key, title, "")

data class AccountPreferenceGroup(val title: String, val preferences: List<AccountPreference>)
data class AccountPreferencesPage(val section: AccountPreferencesSection, val groups: List<AccountPreferenceGroup>) {
    val preferences: List<AccountPreference> get() = groups.flatMap { it.preferences }
}

internal class AccountPreferenceCommand(
    val issuer: Any, val scope: String, val section: AccountPreferencesSection,
    val settingKey: String, val fields: Json.Obj, val value: Json,
)

/** Server-issued setting capabilities, retired by an account, locale or generation change. */
class AccountPreferencesSession(private val account: AccountSession) {
    @JvmOverloads fun load(section: AccountPreferencesSection, http: HttpClient = JvmHttpClient()): ReadResult<AccountPreferencesPage> {
        val context = account.snapshot()
        if (!context.signedIn) return preferenceError(RequestFailure.AUTH, "sign_in_required")
        return load(section, context, guarded(context, http))
    }

    @JvmOverloads fun set(setting: AccountPreferenceToggle, enabled: Boolean,
        http: HttpClient = JvmHttpClient()): ReadResult<AccountPreferencesPage> =
        update(if (enabled) setting.enable else setting.disable, http) { page ->
            page.preferences.filterIsInstance<AccountPreferenceToggle>()
                .singleOrNull { it.key == setting.key }?.enabled == enabled
        }

    @JvmOverloads fun select(setting: AccountPreferenceSelection, choiceKey: String,
        http: HttpClient = JvmHttpClient()): ReadResult<AccountPreferencesPage> {
        val choice = setting.choices.singleOrNull { it.key == choiceKey }
            ?: return preferenceError(RequestFailure.INVALID, "unknown_account_preference_choice")
        return update(choice.command, http) { page ->
            page.preferences.filterIsInstance<AccountPreferenceSelection>().singleOrNull { it.key == setting.key }
                ?.choices?.singleOrNull { it.key == choiceKey }?.selected == true
        }
    }

    private fun update(command: AccountPreferenceCommand, http: HttpClient,
        matches: (AccountPreferencesPage) -> Boolean): ReadResult<AccountPreferencesPage> {
        val context = account.snapshot()
        if (!context.signedIn) return preferenceError(RequestFailure.AUTH, "sign_in_required")
        if (command.issuer !== this || command.scope != context.ledgerScope())
            return preferenceError(RequestFailure.AUTH, "account_preference_session_changed")
        val transport = guarded(context, http)
        val valueFields = command.fields.fields.filterKeys { it in setOf("boolValue", "stringValue", "intValue") }
        val wireFields = mapOf("settingItemId" to requireNotNull(command.fields.fields["settingItemId"]),
            "newValue" to Json.Obj(valueFields))
        val fields = Json.Obj(wireFields).encode().removeSurrounding("{", "}")
        val mutation = signedAction("account/set_setting", fields, context, transport)
        if (!mutation.ok) return ReadResult(error = mutation.error)
        repeat(3) {
            val readback = load(command.section, context, transport)
            if (!readback.ok) return ReadResult(error = readback.error)
            val page = requireNotNull(readback.value)
            if (matches(page)) return ReadResult(page)
        }
        return preferenceError(RequestFailure.HTTP, "account_preference_unconfirmed", mutation.value?.httpStatus)
    }

    private fun load(section: AccountPreferencesSection, context: RequestContext,
        http: HttpClient): ReadResult<AccountPreferencesPage> {
        val response = requestObject(browseQuery(section.browseId, context), http)
        if (!response.ok) return ReadResult(error = response.error)
        if (!current(context)) return preferenceError(RequestFailure.AUTH, "account_preference_session_changed")
        val page = parseAccountPreferences(requireNotNull(response.value), section, context, this)
        return if (page.preferences.isNotEmpty()) ReadResult(page)
        else preferenceError(RequestFailure.UNSUPPORTED, "account_preferences_unavailable")
    }

    private fun current(context: RequestContext): Boolean =
        account.snapshot(context.generation).value?.ledgerScope() == context.ledgerScope()

    private fun guarded(context: RequestContext, http: HttpClient) = object : HttpClient {
        override fun execute(request: HttpRequest): HttpReply {
            fun check() {
                if (!current(context)) throw RequestException(RequestError(RequestFailure.AUTH, "account_preference_session_changed"))
            }
            check()
            return http.execute(request).also { check() }
        }
    }
}

private fun <T> preferenceError(kind: RequestFailure, code: String, status: Int? = null): ReadResult<T> =
    ReadResult(error = RequestError(kind, code, status))

private fun parseAccountPreferences(root: Json.Obj, section: AccountPreferencesSection,
    context: RequestContext, issuer: Any): AccountPreferencesPage {
    val seen = HashSet<String>()
    val groups = root.objects("settingsOptionsRenderer").mapNotNull { group ->
        val preferences = group.arr("options").mapNotNull option@{ raw ->
            val option = raw as? Json.Obj ?: return@option null
            option.obj("settingsSwitchRenderer")?.let { parsePreferenceToggle(it, section, context, issuer) }
                ?: option.obj("dropdownRenderer")?.let { parsePreferenceSelection(it, section, context, issuer) }
        }.filter { seen.add(it.key) }
        if (preferences.isEmpty()) null else AccountPreferenceGroup(group.display("title").orEmpty(), preferences)
    }
    return AccountPreferencesPage(section, groups)
}

private fun parsePreferenceToggle(node: Json.Obj, section: AccountPreferencesSection,
    context: RequestContext, issuer: Any): AccountPreferenceToggle? {
    if (node.bool("requiresConfirmation") == true || node.bool("disabled") == true) return null
    val title = node.display("title")?.takeIf(String::isNotBlank) ?: return null
    val enabled = node.bool("enabled") ?: return null
    val on = preferenceCommand(node.obj("enableServiceEndpoint"), section, context, issuer) ?: return null
    val off = preferenceCommand(node.obj("disableServiceEndpoint"), section, context, issuer) ?: return null
    val onValue = (on.value as? Json.Bool)?.value ?: return null
    val offValue = (off.value as? Json.Bool)?.value ?: return null
    if (onValue == offValue || on.settingKey != off.settingKey ||
        on.fields.scalar("settingItemId") != off.fields.scalar("settingItemId")) return null
    return AccountPreferenceToggle(on.settingKey, title, node.display("subtitle").orEmpty(), enabled, on, off)
}

private fun parsePreferenceSelection(node: Json.Obj, section: AccountPreferencesSection,
    context: RequestContext, issuer: Any): AccountPreferenceSelection? {
    val title = node.display("label")?.takeIf(String::isNotBlank) ?: return null
    val choices = node.arr("entries").mapNotNull { raw ->
        val entry = (raw as? Json.Obj)?.obj("dropdownItemRenderer") ?: return@mapNotNull null
        val label = entry.display("label")?.takeIf(String::isNotBlank) ?: return@mapNotNull null
        val command = preferenceCommand(entry.obj("onSelectCommand"), section, context, issuer) ?: return@mapNotNull null
        if (command.value is Json.Bool) return@mapNotNull null
        AccountPreferenceChoice(textDigest(command.value.encode()), label, entry.bool("isSelected") == true, command)
    }.distinctBy { it.key }
    val key = choices.firstOrNull()?.command?.settingKey ?: return null
    if (choices.any { it.command.settingKey != key } || choices.count { it.selected } != 1) return null
    return AccountPreferenceSelection(key, title, choices)
}

private fun preferenceCommand(endpoint: Json.Obj?, section: AccountPreferencesSection,
    context: RequestContext, issuer: Any): AccountPreferenceCommand? {
    endpoint ?: return null
    if (endpoint.path("commandMetadata", "webCommandMetadata")?.text("apiUrl") != "/youtubei/v1/account/set_setting") return null
    val fields = endpoint.obj("setSettingEndpoint") ?: return null
    val allowed = setOf("settingItemId", "settingItemIdForClient", "boolValue", "stringValue", "intValue")
    if (fields.fields.keys.any { it !in allowed } || fields.scalar("settingItemId").isNullOrBlank()) return null
    val clientItem = fields.scalar("settingItemIdForClient") ?: fields.scalar("settingItemId") ?: return null
    val values = listOfNotNull(fields.fields["boolValue"], fields.fields["stringValue"], fields.fields["intValue"])
    if (values.size != 1 || values.single() !is Json.Bool && values.single() !is Json.Str && values.single() !is Json.Num) return null
    return AccountPreferenceCommand(issuer, context.ledgerScope(), section, textDigest(section.name + ":" + clientItem), fields, values.single())
}
