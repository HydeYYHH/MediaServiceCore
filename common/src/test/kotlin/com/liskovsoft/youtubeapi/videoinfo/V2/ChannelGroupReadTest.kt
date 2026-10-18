package com.liskovsoft.youtubeapi.videoinfo.V2

import org.junit.Assert.*
import org.junit.Test

class ChannelGroupReadTest {
    private fun fixture(name: String) = javaClass.getResourceAsStream("/fixtures/$name")!!
        .bufferedReader(Charsets.UTF_8).use { it.readText() }

    @Test fun importsRecordedExternalFormatsWithStableIdentities() {
        for (name in listOf("groups-pockettube.json", "groups-newpipe.json", "groups-grayjay.json")) {
            val text = fixture(name)
            val first = readChannelGroups(text)
            assertTrue(name + ": " + first.error, first.ok)
            assertTrue(first.value!!.flatMap { it.channels }.isNotEmpty())
            assertEquals(first.value, readChannelGroups(text).value)
            assertEquals(first.value, readChannelGroups("\uFEFF" + text).value)
            val groups = ChannelGroups()
            assertTrue(groups.import(text).ok)
            assertTrue(groups.import(text).ok)
            assertEquals(first.value!!.size, groups.read().size)
        }
    }

    @Test fun resolvesHandlesOnlyWhenRequestedAndKeepsFailedImportsAtomic() {
        val text = "{\"subscriptions\":[{\"service_id\":0,\"url\":\"https://www.youtube.com/@creator\",\"name\":\"Creator\"}]}"
        assertEquals("unresolved_channel_id", readChannelGroups(text).error!!.code)
        val parsed = readChannelGroups(text, resolve = { "UCresolved" }).value!!
        assertEquals("UCresolved", parsed.single().channels.single().id)
        val groups = ChannelGroups(parsed)
        assertFalse(groups.import("{\"ysc_collection\":{\"Broken\":\"icon\"}}", replace = true).ok)
        assertEquals(parsed, groups.read())
        assertFalse(readChannelGroups("{bad}").ok)
    }

    @Test fun ignoresOtherPlatformsInAMixedGrayJayGroup() {
        val text = """[{"id":"group","name":"Mixed","urls":["https://www.youtube.com/channel/UCone","https://vimeo.com/creator"]}]"""
        assertEquals(listOf("UCone"), readChannelGroups(text).value!!.single().channels.map { it.id })
    }
}
