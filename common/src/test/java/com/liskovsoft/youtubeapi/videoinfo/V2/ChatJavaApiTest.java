package com.liskovsoft.youtubeapi.videoinfo.V2;

import java.util.Collections;
import java.util.List;
import kotlin.Unit;
import org.junit.Test;
import static org.junit.Assert.*;

/** Java hosts call the real @JvmOverloads constructors rather than Kotlin's default bridge. */
public class ChatJavaApiTest {
    @Test public void pageOverloadsPreserveExplicitEventsAndDefaultMissingCapabilities() {
        ChatMessage message = new ChatMessage("m", "Owner", "Hello", null, 42L, "liveChatTextMessageRenderer", "channel");
        ChatAction action = new ChatAction.Add(message);
        List<ChatMessage> messages = Collections.singletonList(message);
        List<ChatRemoval> removals = Collections.emptyList();
        List<ChatAction> actions = Collections.singletonList(action);
        List<ChatEvent> events = Collections.singletonList(new ChatEvent(1000L, action));

        ChatPage basic = new ChatPage(messages, removals, "next", 500L);
        assertEquals(messages, basic.getMessages());
        assertEquals("next", basic.getNextKey());
        assertEquals(500L, basic.getWaitMs());
        assertTrue(basic.getActions().isEmpty());
        assertTrue(basic.getEvents().isEmpty());
        assertFalse(basic.getReplay());
        assertNull(basic.getSendParams());
        assertNull(basic.getPostingRestriction());

        ChatPage ordered = new ChatPage(messages, removals, "next", 500L, actions);
        assertSame(action, ordered.getActions().get(0));
        assertTrue(ordered.getEvents().isEmpty());

        ChatPage timed = new ChatPage(messages, removals, "next", 500L, actions, events);
        assertEquals(Long.valueOf(1000L), timed.getEvents().get(0).getOffsetMs());
        assertFalse(timed.getReplay());

        ChatPage replay = new ChatPage(messages, removals, "next", 500L, actions, events, true);
        assertTrue(replay.getReplay());
        assertNull(replay.getSendParams());

        ChatPage posting = new ChatPage(messages, removals, "next", 500L, actions, events, false, "address");
        assertFalse(posting.getReplay());
        assertEquals("address", posting.getSendParams());
        assertNull(posting.getPostingRestriction());
    }

    @Test public void defaultConnectionCanBeClosedBeforeItStartsWithoutCallingBack() {
        ChatConnection connection = new ChatConnection();
        connection.close();
        ReadResult<Boolean> stopped = connection.listen("unused-key", page -> {
            fail("A closed connection must not deliver any page");
            return Unit.INSTANCE;
        });
        assertTrue(stopped.getOk());
        assertEquals(Boolean.TRUE, stopped.getValue());
        assertNull(stopped.getError());
    }
}
