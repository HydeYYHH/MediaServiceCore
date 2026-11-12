package com.liskovsoft.youtubeapi.videoinfo.V2;

import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import static org.junit.Assert.*;

/** Direct Java calls preserve the old BrowseTab constructor and exercise both session constructor entries. */
public class PublicChannelSessionConsumerTest {
    @Test public void javaScreenUsesSemanticTabsAndReusesItsOpenedLandingPage() {
        String id = "UC_x5XG1OV2P6uZZ5FSM9Ttw";
        AtomicInteger calls = new AtomicInteger();
        HttpClient http = request -> {
            calls.incrementAndGet();
            assertTrue(request.getBody().contains(id));
            return new HttpReply(200, "{\"metadata\":{\"channelMetadataRenderer\":{\"externalId\":\"" + id + "\",\"title\":\"Lessons\"}},"
                + "\"contents\":{\"twoColumnBrowseResultsRenderer\":{\"tabs\":[{\"tabRenderer\":{\"title\":\"课程\",\"tabIdentifier\":\"videos\",\"selected\":true}}]}}}", Collections.emptyMap());
        };
        ChannelSession injected = new ChannelSession(new AccountSession(), http);
        ReadResult<ChannelHandle> opened = injected.open(id);
        assertTrue(opened.getOk());
        ChannelHandle handle = opened.getValue();
        assertEquals("Lessons", handle.getChannel().getTitle());
        assertEquals(Collections.singletonList(ChannelTabKind.VIDEOS), handle.getAvailableTabs());
        ChannelTabPage videos = handle.openTab(ChannelTabKind.VIDEOS).getValue();
        assertEquals(ChannelTabKind.VIDEOS, videos.getKind());
        assertSame(handle.getChannel().getPage(), videos.getPage());
        assertNull(videos.getNext());
        assertEquals(1, calls.get());

        // Kotlin internal constructors remain JVM-public; copying an owner cannot manufacture a grant.
        assertEquals("foreign_channel_cursor", handle.next(new ChannelTabCursor(handle, ChannelTabKind.VIDEOS, "copied-token")).getError().getCode());
        assertEquals("foreign_channel_posts_cursor", handle.nextPosts(new ChannelPostsCursor(handle, "copied-token")).getError().getCode());
        assertEquals(1, calls.get());

        ChannelSession withDefaultTransport = new ChannelSession(new AccountSession());
        ReadResult<ChannelHandle> malformed = withDefaultTransport.open("https://youtube.com/@lessons");
        assertFalse(malformed.getOk());
        assertEquals(RequestFailure.INVALID, malformed.getError().getKind());
        assertEquals("invalid_channel_identity", malformed.getError().getCode());
    }

    @Test public void legacyAndSemanticBrowseTabConstructorsHaveExplicitNavigationMeaning() {
        BrowseTab oldModel = new BrowseTab("Videos", "UCopaque", "server-videos", false);
        assertEquals(ChannelTabKind.UNKNOWN, oldModel.getKind());
        assertEquals("Videos", oldModel.getTitle());
        assertEquals("UCopaque", oldModel.getBrowseId());
        assertEquals("server-videos", oldModel.getParams());
        assertFalse(oldModel.getSelected());
        BrowseTab recognized = new BrowseTab("课程", "UCopaque", "server-videos", true, ChannelTabKind.VIDEOS);
        assertEquals(ChannelTabKind.VIDEOS, recognized.getKind());
        assertTrue(recognized.getSelected());
        assertEquals("课程", recognized.getTitle());
    }
}
