package com.liskovsoft.youtubeapi.videoinfo.V2

/** Bounded result cache with a write fence for each video/session, including in-flight loads. */
internal class PlaybackResults(private val capacity: Int, private val now: () -> Long) {
    data class Ticket(val scope: String, val version: Long)
    private data class Key(val scope: String, val demand: String)
    private data class Entry(val value: PlaybackLoad, val expires: Long)
    private val versions = java.util.LinkedHashMap<String, Long>(16, .75f, true)
    private val entries = java.util.LinkedHashMap<Key, Entry>(16, .75f, true)
    private var sequence = 0L
    private var epoch = 0L

    @Synchronized fun generation() = epoch

    @Synchronized fun ticket(scope: String, refresh: Boolean = false): Ticket {
        val version = versions[scope]?.takeUnless { refresh } ?: (++sequence).also {
            versions[scope] = it
            entries.keys.removeAll { key -> key.scope == scope }
        }
        while (versions.size > capacity) {
            val oldest = versions.keys.first()
            versions.remove(oldest)
            entries.keys.removeAll { it.scope == oldest }
        }
        return Ticket(scope, version)
    }

    @Synchronized fun get(ticket: Ticket, demand: String): PlaybackLoad? {
        if (versions[ticket.scope] != ticket.version) return null
        val key = Key(ticket.scope, demand)
        val entry = entries[key] ?: return null
        if (entry.expires <= now()) { entries.remove(key); return null }
        return entry.value
    }

    @Synchronized fun put(ticket: Ticket, demand: String, value: PlaybackLoad, ttlMs: Long) {
        if (versions[ticket.scope] != ticket.version || ttlMs <= 0) return
        entries[Key(ticket.scope, demand)] = Entry(value, now() + ttlMs)
        while (entries.size > capacity) entries.remove(entries.keys.first())
    }

    @Synchronized fun clear() {
        epoch++
        versions.clear()
        entries.clear()
    }
}
