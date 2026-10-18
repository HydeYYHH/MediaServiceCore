package com.liskovsoft.youtubeapi.videoinfo.V2

internal class ConnectionWait(private val delay: ((Long) -> Unit)? = null) {
    private val closed = java.util.concurrent.CountDownLatch(1)
    fun close() = closed.countDown()
    fun pause(milliseconds: Long) {
        if (closed.count == 0L) return
        val custom = delay
        if (custom != null) custom(milliseconds)
        else closed.await(milliseconds, java.util.concurrent.TimeUnit.MILLISECONDS)
    }
}
