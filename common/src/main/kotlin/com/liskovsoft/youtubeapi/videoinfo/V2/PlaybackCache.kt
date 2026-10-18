package com.liskovsoft.youtubeapi.videoinfo.V2

internal class TimedCache<K, V>(private val capacity: Int, private val now: () -> Long) {
    private data class Entry<V>(val value: V, val expires: Long)
    private val entries = java.util.LinkedHashMap<K, Entry<V>>(16, 0.75f, true)
    private var epoch = 0L
    init { require(capacity > 0) }
    @Synchronized fun generation() = epoch
    @Synchronized fun get(key: K): V? {
        val entry = entries[key] ?: return null
        if (entry.expires <= now()) { entries.remove(key); return null }
        return entry.value
    }
    @Synchronized fun put(key: K, value: V, ttlMs: Long, generation: Long = epoch) {
        if (generation != epoch || ttlMs <= 0) return
        entries[key] = Entry(value, now() + ttlMs)
        while (entries.size > capacity) entries.remove(entries.keys.first())
    }
    @Synchronized fun clear() { epoch++; entries.clear() }
}
class MemoryPlaybackCache(maxEntries: Int = 128, private val ttlMs: Long = 30 * 60 * 1000,
    now: () -> Long = System::currentTimeMillis, private val maxValueChars: Int = 4 * 1024 * 1024) : PlaybackCache {
    private val entries = TimedCache<String, String>(maxEntries, now)
    private fun key(kind: String, url: String, value: String = "") = listOf(kind, url, value).joinToString("\u0000")
    private fun save(key: String, value: String) { if (value.length <= maxValueChars) entries.put(key, value, ttlMs) }
    override fun script(url: String) = entries.get(key("script", url))
    override fun saveScript(url: String, script: String) = save(key("script", url), script)
    override fun signature(url: String, value: String) = entries.get(key("signature", url, value))
    override fun saveSignature(url: String, value: String, solved: String) = save(key("signature", url, value), solved)
    override fun throttle(url: String, value: String) = entries.get(key("throttle", url, value))
    override fun saveThrottle(url: String, value: String, solved: String) = save(key("throttle", url, value), solved)
    fun clear() = entries.clear()
}
