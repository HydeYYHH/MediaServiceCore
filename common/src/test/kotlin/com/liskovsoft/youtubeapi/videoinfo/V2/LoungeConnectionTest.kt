package com.liskovsoft.youtubeapi.videoinfo.V2
import org.junit.Assert.*
import org.junit.Test
import java.io.StringReader
class LoungeConnectionTest {
    @Test fun readsFragmentedFramesAndDropsDuplicateCommands() {
        val frames=LoungeFrames()
        val body="""[[1,["noop"]],[2,["seekTo",{"newTime":"12.5"}]]]"""
        val wire=body.length.toString()+"\n"+body
        val commands=wire.flatMap { frames.push(it.toString()) }
        assertEquals("seekTo",commands.single().type);assertEquals(2L,frames.lastId)
        assertTrue(frames.push(wire).isEmpty());frames.finish()
    }
    @Test fun reconnectsWithANewTokenAndClosesTheInjectedStream() {
        var tokens=0;var binds=0;var closed=false
        val http=object:HttpClient { override fun execute(request:HttpRequest):HttpReply {
            binds++
            return if(binds==1) HttpReply(403,"") else HttpReply(200,"[[0,[\"c\",\"sid\"]],[1,[\"S\",\"gsid\"]]]")
        }}
        val transport=object:LoungeTransport { override fun open(request:HttpRequest):java.io.Reader {
            assertEquals("token2",urlParameter(request.url,"loungeIdToken"))
            val body="[[2,[\"play\"]]]"
            return object:StringReader(body.length.toString()+"\n"+body){override fun close(){closed=true;super.close()}}
        }}
        val connection=LoungeConnection("screen","device",{tokens++;LoungeScreen("screen","token$tokens")},http,transport,wait={})
        assertTrue(connection.listen { assertEquals("play",it.type);connection.close() }.ok)
        assertEquals(2,tokens);assertTrue(closed)
    }
    @Test fun boundsConsecutiveConnectionFailures() {
        val http=object:HttpClient { override fun execute(request:HttpRequest)=HttpReply(503,"") }
        val transport=object:LoungeTransport { override fun open(request:HttpRequest)=StringReader("") }
        val c=LoungeConnection("s","d",{LoungeScreen("s","t")},http,transport,wait={},maxReconnects=1)
        assertEquals("lounge_reconnect_exhausted",c.listen{}.error!!.code)
    }
}
