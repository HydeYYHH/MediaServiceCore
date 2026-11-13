package com.liskovsoft.youtubeapi.videoinfo.V2
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

internal class ConnectionWait(private val delay: ((Long) -> Unit)? = null) {
    private val closed = CountDownLatch(1)
    fun close() = closed.countDown()
    fun pause(milliseconds: Long) {
        if (closed.count == 0L) return
        val custom = delay
        if (custom != null) custom(milliseconds)
        else closed.await(milliseconds, TimeUnit.MILLISECONDS)
    }
}
