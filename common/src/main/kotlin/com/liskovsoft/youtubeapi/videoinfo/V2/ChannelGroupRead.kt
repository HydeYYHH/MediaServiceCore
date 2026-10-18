package com.liskovsoft.youtubeapi.videoinfo.V2

enum class GroupFormat { MSC, POCKETTUBE, NEWPIPE, GRAYJAY }

private class GroupEnd(val code: String) : RuntimeException()
private fun stableGroupId(format: GroupFormat, title: String) = java.util.UUID.nameUUIDFromBytes(
    (format.name + ":" + title).toByteArray(Charsets.UTF_8)).toString()

// PocketTubeService / NewPipeService / GrayJayService, upstream 7c2451ea, channelgroups/importing.
fun readChannelGroups(text: String, format: GroupFormat? = null,
    resolve: ((String) -> String?)? = null): ReadResult<List<ChannelGroup>> {
    var root = jsonValue(text.removePrefix("\uFEFF")) ?: return ReadResult(error = RequestError(RequestFailure.PARSE, "invalid_group_export"))
    if (root is Json.Str) root = jsonValue(root.value) ?: return ReadResult(error = RequestError(RequestFailure.PARSE, "invalid_group_export"))
    val objectRoot = root as? Json.Obj
    val selected = format ?: when {
        objectRoot?.int("version") == 1 && objectRoot.fields["groups"] is Json.Arr -> GroupFormat.MSC
        objectRoot?.fields?.get("ysc_collection") is Json.Obj -> GroupFormat.POCKETTUBE
        objectRoot?.fields?.get("subscriptions") is Json.Arr -> GroupFormat.NEWPIPE
        root is Json.Arr -> GroupFormat.GRAYJAY
        else -> return ReadResult(error = RequestError(RequestFailure.PARSE, "unknown_group_format"))
    }
    if (selected == GroupFormat.MSC) return ChannelGroups().import(root.encode(), replace = true, format = GroupFormat.MSC)
    fun channel(value: String, title: String? = null): GroupChannel {
        val id = channelIdFromUrl(value) ?: resolve?.invoke(value) ?: throw GroupEnd("unresolved_channel_id")
        if (!id.startsWith("UC") || !videoIdAccepted(id)) throw GroupEnd("invalid_channel_id")
        return GroupChannel(id, title, null)
    }
    fun channels(values: List<Json>, youtubeOnly: Boolean = false) = values.mapNotNull {
        val value = (it as? Json.Str)?.value ?: throw GroupEnd("invalid_channel_url")
        val host = try { java.net.URI(value).host } catch (_: Exception) { null }
        if (youtubeOnly && host != null && host !in youtubeHosts) null else channel(value)
    }.distinctBy { it.id }
    return try {
        val groups = when (selected) {
            GroupFormat.POCKETTUBE -> {
                val source = objectRoot ?: throw GroupEnd("invalid_pockettube_export")
                val collection = source.obj("ysc_collection") ?: throw GroupEnd("invalid_pockettube_export")
                collection.fields.map { (title, nested) ->
                    val entries = source.fields[title] as? Json.Arr ?: nested as? Json.Arr ?: throw GroupEnd("invalid_pockettube_group")
                    ChannelGroup(stableGroupId(selected, title), title, null, channels(entries.items))
                }
            }
            GroupFormat.NEWPIPE -> {
                val entries = objectRoot?.fields?.get("subscriptions") as? Json.Arr ?: throw GroupEnd("invalid_newpipe_export")
                val items = entries.items.mapNotNull { value ->
                    val entry = value as? Json.Obj ?: throw GroupEnd("invalid_newpipe_channel")
                    if (entry.int("service_id")?.let { it != 0 } == true) null else
                        channel(entry.text("url") ?: throw GroupEnd("missing_channel_url"), entry.text("name"))
                }.distinctBy { it.id }
                listOf(ChannelGroup(stableGroupId(selected, "subscriptions"), "subscriptions", null, items))
            }
            GroupFormat.GRAYJAY -> (root as? Json.Arr ?: throw GroupEnd("invalid_grayjay_export")).items.map { value ->
                val group = when (value) { is Json.Obj -> value; is Json.Str -> jsonObject(value.value); else -> null }
                    ?: throw GroupEnd("invalid_grayjay_group")
                val title = group.text("name") ?: throw GroupEnd("missing_group_title")
                val urls = group.fields["urls"] as? Json.Arr ?: throw GroupEnd("missing_group_channels")
                ChannelGroup(group.scalar("id")?.takeIf { it.isNotBlank() } ?: stableGroupId(selected, title), title,
                    group.obj("image")?.text("url"), channels(urls.items, youtubeOnly = true))
            }
            GroupFormat.MSC -> emptyList()
        }
        if (groups.any { it.title.isBlank() } || groups.map { it.id }.distinct().size != groups.size) throw GroupEnd("invalid_group_identity")
        ReadResult(groups)
    } catch (error: GroupEnd) { ReadResult(error = RequestError(RequestFailure.PARSE, error.code)) }
    catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
        ReadResult(error = RequestError(RequestFailure.CANCELLED, "cancelled"))
    } catch (_: Exception) { ReadResult(error = RequestError(RequestFailure.TRANSPORT, "channel_resolution_failed")) }
}

private val youtubeHosts = setOf("youtube.com", "www.youtube.com", "m.youtube.com", "music.youtube.com")
private fun channelIdFromUrl(value: String): String? {
    if (value.startsWith("UC") && videoIdAccepted(value)) return value
    val uri = try { java.net.URI(value) } catch (_: Exception) { return null }
    if (uri.host !in youtubeHosts) return null
    val parts = uri.path.orEmpty().trim('/').split('/')
    return parts.getOrNull(1)?.takeIf { parts.firstOrNull() == "channel" && it.startsWith("UC") && videoIdAccepted(it) }
}
