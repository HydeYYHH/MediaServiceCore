package com.liskovsoft.youtubeapi.videoinfo.V2;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.*;

/** Boundary defaults used by Java hosts when they reconstruct UI data or interpret transport acknowledgements. */
public class PublicHostBoundaryTest {
    @Test public void shortMutationRepliesDoNotInventConfirmationAndTheirLogsOmitResponseBodies() {
        String privateBody = "{\"private\":\"response-only-data\"}";
        List<ActionReply> replies = Arrays.asList(
            new ActionReply("OK", privateBody),
            new ActionReply("OK", privateBody, ActionState.CONFIRMED),
            new ActionReply("OK", privateBody, ActionState.CONFIRMED, 200),
            new ActionReply("OK", privateBody, ActionState.CONFIRMED, 200, "status")
        );
        for (int i = 0; i < replies.size(); i++) {
            ActionReply reply = replies.get(i);
            assertEquals("OK", reply.getStatus());
            assertEquals(privateBody, reply.getBody());
            assertEquals(i == 0 ? ActionState.UNCONFIRMED : ActionState.CONFIRMED, reply.getState());
            assertEquals(i != 0, reply.getConfirmed());
            assertEquals(i >= 2 ? Integer.valueOf(200) : null, reply.getHttpStatus());
            assertEquals(i >= 3 ? "status" : null, reply.getResponseShape());
            assertTrue(reply.getMetadata().isEmpty());
            assertEquals("ActionReply(status=OK, state=" + reply.getState() + ")", reply.toString());
            assertFalse(reply.toString().contains("response-only-data"));
        }
    }

    @Test public void hostBuiltBrowsePagesKeepOnlyExplicitMessagesAndServerActionDestinations() {
        BrowsePage parsed = BrowsePageKt.browsePage("{\"buttonRenderer\":{\"text\":{\"simpleText\":\"Hide\"},"
            + "\"serviceEndpoint\":{\"commandMetadata\":{\"webCommandMetadata\":{\"apiUrl\":\"/youtubei/v1/feedback\"}},"
            + "\"feedbackEndpoint\":{\"feedbackToken\":\"server-command\"}}}}");
        PageAction action = parsed.getActions().get(0);
        assertEquals("/youtubei/v1/feedback", action.getEndpoint().getApiUrl());
        assertEquals("feedbackEndpoint", action.getEndpoint().getName());
        assertTrue(action.getEndpoint().getPayload().contains("server-command"));

        MediaItem item = new MediaItem("video", "lesson1", "First lesson", "Teacher", "https://img.example/1.jpg", "1:00");
        List<MediaItem> items = Collections.singletonList(item);
        List<MediaGroup> groups = Collections.singletonList(new MediaGroup("Course", items, "group-next"));
        List<BrowseTab> tabs = Collections.singletonList(new BrowseTab("Videos", "UCcourse", null, true));
        List<String> messages = Collections.singletonList("Some results are unavailable");
        List<PageAction> actions = Collections.singletonList(action);
        List<BrowsePage> pages = Arrays.asList(
            new BrowsePage(items, groups, tabs, "next"),
            new BrowsePage(items, groups, tabs, "next", messages),
            new BrowsePage(items, groups, tabs, "next", messages, actions)
        );
        for (int i = 0; i < pages.size(); i++) {
            BrowsePage page = pages.get(i);
            assertEquals("First lesson", page.getItems().get(0).getTitle());
            assertEquals("Course", page.getGroups().get(0).getTitle());
            assertEquals("Videos", page.getTabs().get(0).getTitle());
            assertEquals("next", page.getNextKey());
            assertEquals(i >= 1 ? messages : Collections.emptyList(), page.getMessages());
            assertEquals(i >= 2 ? actions : Collections.emptyList(), page.getActions());
            assertNull(page.getVisitor());
        }
    }

    @Test public void defaultLocalHistoryAndEmptyResultsDoNotPretendToBeNetworkSuccess() {
        ReadResult<BrowsePage> pending = new ReadResult<>();
        assertFalse(pending.getOk());
        assertNull(pending.getValue());
        assertNull(pending.getError());

        SearchHistory history = new SearchHistory();
        RequestContext anonymous = new RequestContext();
        history.record(" first ", anonymous);
        history.record("second", anonymous);
        history.record("first", anonymous);
        assertEquals(Arrays.asList("first", "second"), history.read(anonymous));
        history.remove("first", anonymous);
        assertEquals(Collections.singletonList("second"), history.read(anonymous));
        history.clear(anonymous);
        assertTrue(history.read(anonymous).isEmpty());

        Map<String, String> wireHeaders = Collections.singletonMap("Retry-After", "3");
        HttpReply reply = new HttpReply(429, "", wireHeaders);
        assertEquals(wireHeaders, reply.getHeaders());
        assertEquals("3", reply.header("retry-after"));
        assertEquals(429, reply.getStatus());
    }

    @Test public void defaultJvmTransportRejectsUnsupportedMethodsBeforeOpeningAConnection() {
        JvmHttpClient client = new JvmHttpClient();
        HttpRequest request = new HttpRequest("PATCH", "https://unused.invalid", Collections.emptyMap(), "");
        assertThrows(IllegalArgumentException.class, () -> client.execute(request));
    }
}
