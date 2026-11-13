package com.liskovsoft.youtubeapi.videoinfo.V2
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger
class PlaybackSessionTest {
    @Test fun cancellingAFollowerLeavesTheLeaderRunning() {
        val entered=CountDownLatch(1);val release=CountDownLatch(1)
        val calls=AtomicInteger()
        val http=object:HttpClient { override fun execute(request:HttpRequest):HttpReply {
            calls.incrementAndGet();entered.countDown();release.await(5,TimeUnit.SECONDS)
            return HttpReply(200,"{\"streamingData\":{\"formats\":[{\"url\":\"https://media\"}]}}")
        }}
        val session=PlaybackSession(http);val pool=Executors.newSingleThreadExecutor()
        var cancelled:ReadResult<PlaybackLoad>?=null
        val follower=Thread {cancelled=session.load("v")}
        try {
            val leader=pool.submit(Callable {session.load("v")})
            assertTrue(entered.await(2,TimeUnit.SECONDS));follower.start()
            val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(2)
            while(follower.state!=Thread.State.WAITING && System.nanoTime()<deadline) Thread.yield()
            assertEquals(Thread.State.WAITING,follower.state)
            follower.interrupt();follower.join(2000)
            assertEquals(RequestFailure.CANCELLED,cancelled!!.error!!.kind)
            release.countDown();assertTrue(leader.get(2,TimeUnit.SECONDS).ok);assertEquals(1,calls.get())
        } finally {release.countDown();follower.interrupt();pool.shutdownNow()}
    }
    @Test fun coalescesTheSameDemandWithoutSharingAnotherAccountsLoad() {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val calls = AtomicInteger()
        val http = object : HttpClient { override fun execute(request: HttpRequest): HttpReply {
            calls.incrementAndGet(); entered.countDown(); release.await(2, TimeUnit.SECONDS)
            return HttpReply(200, """{"streamingData":{"formats":[{"url":"https://media"}]}}""")
        }}
        val session = PlaybackSession(http); val pool = Executors.newFixedThreadPool(3)
        try {
            val first = pool.submit(Callable { session.load("v", RequestContext(accountId = "a")) })
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            val other = pool.submit(Callable { session.load("v", RequestContext(accountId = "b")) })
            val joined = pool.submit(Callable { session.load("v", RequestContext(accountId = "a")) })
            Thread.sleep(50); release.countDown()
            val a = first.get(2, TimeUnit.SECONDS).value!!
            assertEquals(a.source, joined.get(2, TimeUnit.SECONDS).value!!.source)
            assertNotEquals(a.source, other.get(2, TimeUnit.SECONDS).value!!.source); assertEquals(2, calls.get())
        } finally { release.countDown(); pool.shutdownNow() }
    }
    @Test fun returnsTheUnderlyingFailureAndAllowsANewAttempt() {
        val http = object : HttpClient { override fun execute(request: HttpRequest) = HttpReply(503, "{}") }
        assertEquals(RequestFailure.HTTP, PlaybackSession(http).load("v").error!!.kind)
    }
}
