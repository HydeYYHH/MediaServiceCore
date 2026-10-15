package com.liskovsoft.youtubeapi.videoinfo.V2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LoungeCommandTest {
    @Test
    fun holdsTheNoopLine() {
        val listen = LoungeListen()
        assertNull(listen.push("[[1,[\"noop\"]"))
        assertNull(listen.push("]"))
        assertNull(listen.push(",[2,[\"play\"]]"))
        val commands = listen.push("]")
        assertEquals(2, commands?.size)
        assertEquals("noop", commands?.get(0)?.type)
        assertEquals("play", commands?.get(1)?.type)
    }

    @Test
    fun readsPlaybackCommands() {
        val body = "128\n" +
            "[[0,[\"pause\"]],[1,[\"seekTo\",{\"newTime\":\"12.5\"}]]," +
            "[2,[\"setPlaylist\",{\"videoId\":\"dQw4w9WgXcQ\"}]]," +
            "[3,[\"setVolume\",{\"volume\":40}]]]"
        val commands = loungeCommands(body)
        assertEquals("pause", commands[0].type)
        assertEquals("seekTo", commands[1].type)
        assertEquals("12.5", commands[1].seconds)
        assertEquals("setPlaylist", commands[2].type)
        assertEquals("dQw4w9WgXcQ", commands[2].videoId)
        assertEquals("setVolume", commands[3].type)
        assertEquals(Integer.valueOf(40), commands[3].volume)
    }
}
