package com.liskovsoft.youtubeapi.videoinfo.V2

interface PlaybackCache {
    fun script(url: String): String?
    fun saveScript(url: String, script: String)
    fun signature(url: String, value: String): String?
    fun saveSignature(url: String, value: String, solved: String)
    fun throttle(url: String, value: String): String?
    fun saveThrottle(url: String, value: String, solved: String)
}

class PlaybackLock {
    private val loads = HashMap<String, Slot>()

    fun load(videoId: String, block: () -> PlaybackLoad): PlaybackLoad {
        val slot: Slot
        val leader: Boolean
        synchronized(loads) {
            val current = loads[videoId]
            if (current == null) {
                slot = Slot()
                loads[videoId] = slot
                leader = true
            } else {
                slot = current
                leader = false
            }
        }
        if (!leader) return slot.await()
        try {
            val result = block()
            slot.succeed(result)
            return result
        } catch (failure: Throwable) {
            slot.fail(failure)
            throw failure
        } finally {
            synchronized(loads) { loads.remove(videoId) }
        }
    }

    private class Slot {
        private var done = false
        private var result: PlaybackLoad? = null
        private var error: Throwable? = null

        fun succeed(value: PlaybackLoad) = synchronized(this) {
            result = value
            done = true
            (this as java.lang.Object).notifyAll()
        }

        fun fail(failure: Throwable) = synchronized(this) {
            error = failure
            done = true
            (this as java.lang.Object).notifyAll()
        }

        fun await(): PlaybackLoad = synchronized(this) {
            while (!done) (this as java.lang.Object).wait()
            val failure = error
            if (failure != null) throw failure
            return result!!
        }
    }
}
