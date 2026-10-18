package com.liskovsoft.youtubeapi.videoinfo.V2
import org.junit.Assert.*
import org.junit.Test
class ChannelGroupsTest {
    @Test fun editsAndRoundTripsStableIdsWithoutSharingMutableLists() {
        val input=mutableListOf(GroupChannel("UC1","Ada",null))
        val groups=ChannelGroups(newId={"stable"})
        val group=groups.create("Music",channels=input);input.clear()
        assertEquals(1,groups.find(group.id)!!.channels.size);assertTrue(groups.rename(group.id,"Renamed"))
        val copy=ChannelGroups()
        assertTrue(copy.import(groups.export(),replace=true).ok);assertEquals(groups.read(),copy.read())
        assertFalse(copy.import("{\"version\":1,\"groups\":[{}]}").ok);assertEquals(groups.read(),copy.read())
        assertTrue(copy.remove("stable"));assertTrue(copy.read().isEmpty())
    }
}
