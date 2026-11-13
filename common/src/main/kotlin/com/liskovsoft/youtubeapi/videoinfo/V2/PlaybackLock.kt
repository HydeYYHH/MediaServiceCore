package com.liskovsoft.youtubeapi.videoinfo.V2
import java.util.concurrent.CountDownLatch

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
            // A waiter may immediately restart after an owner was cancelled. Retire
            // this flight before waking it, so it cannot rejoin the completed failure.
            synchronized(loads) { if (loads[videoId] === slot) loads.remove(videoId) }
            slot.succeed(result)
            return result
        } catch (failure: Throwable) {
            synchronized(loads) { if (loads[videoId] === slot) loads.remove(videoId) }
            slot.fail(failure)
            throw failure
        } finally {
            synchronized(loads) { if (loads[videoId] === slot) loads.remove(videoId) }
        }
    }

    private class Slot {
        private val done = CountDownLatch(1)
        private var result: PlaybackLoad? = null
        private var error: Throwable? = null

        fun succeed(value: PlaybackLoad) {
            result = value
            done.countDown()
        }

        fun fail(failure: Throwable) {
            error = failure
            done.countDown()
        }

        fun await(): PlaybackLoad {
            done.await()
            val failure = error
            if (failure != null) throw failure
            return result!!
        }
    }
}
