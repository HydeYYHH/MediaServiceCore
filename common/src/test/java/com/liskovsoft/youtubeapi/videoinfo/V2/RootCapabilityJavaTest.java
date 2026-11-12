package com.liskovsoft.youtubeapi.videoinfo.V2;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import static org.junit.Assert.*;

/** Kotlin internal constructors are callable from Java; issuance must be an instance fact. */
public class RootCapabilityJavaTest {
    private static HttpReply reply(String body) { return new HttpReply(200, body, Collections.emptyMap()); }
    private static void rejected(ReadResult<?> result, String code) {
        assertEquals(RequestFailure.INVALID, result.getError().getKind());
        assertEquals(code, result.getError().getCode());
    }

    @Test public void reconstructedDiscussionRootsCannotIssueOrUseAnyCommentCapability() {
        RequestContext context = new RequestContext("owner", null, "en", "US", 4L, null,
            new WebSession("SAPISID=fixture", 0), null);
        AccountSession account = new AccountSession(context);
        AtomicInteger reads = new AtomicInteger(), writes = new AtomicInteger();
        String openingBody = "{\"videoDetails\":{\"videoId\":\"video\"},\"contents\":{\"twoColumnWatchNextResults\":{\"results\":{\"results\":{\"contents\":["
            + "{\"itemSectionRenderer\":{\"sectionIdentifier\":\"comment-item-section\",\"contents\":[]}}]}}}},"
            + "\"createCommentEndpoint\":{\"createCommentParams\":\"server-issued\"}}";
        HttpClient http = request -> {
            if (request.getUrl().contains("/comment/")) { writes.incrementAndGet(); return reply("{\"status\":\"STATUS_SUCCEEDED\"}"); }
            reads.incrementAndGet(); return reply(openingBody);
        };
        DiscussionSession issuer = new DiscussionSession(account, http);
        CommentThread issued = issuer.openVideo("video").getValue();
        assertTrue(issued.load().getValue().getCanCreate());
        assertTrue(issued.create("Fixture draft").getValue().getConfirmed());
        assertEquals(1, reads.get()); assertEquals(1, writes.get());

        // A direct token without an owned comments collection is not a server-issued discussion grant.
        AtomicInteger unscopedWrites = new AtomicInteger();
        HttpClient unscopedHttp = request -> {
            if (request.getUrl().contains("/comment/")) unscopedWrites.incrementAndGet();
            return reply("{\"videoDetails\":{\"videoId\":\"video\"},\"contents\":{\"twoColumnWatchNextResults\":{\"results\":{\"results\":{\"contents\":[]}}}},"
                + "\"createCommentEndpoint\":{\"createCommentParams\":\"unscoped-token\"}}");
        };
        CommentThread unscoped = new DiscussionSession(new AccountSession(context), unscopedHttp).openVideo("video").getValue();
        assertFalse(unscoped.load().getValue().getCanCreate());
        assertEquals("missing_comment_creation_capability", unscoped.create("Never sent").getError().getCode());
        assertEquals(0, unscopedWrites.get());

        WatchPage forgedWatch = WatchPageKt.watchPage("{\"createCommentEndpoint\":{\"createCommentParams\":\"caller-token\"}}", "video");
        DiscussionOpening forgedOpening = new DiscussionOpening(new Json.Obj(Map.of()), forgedWatch, null);
        // Keep the old constructor shape compilable and also test copying the actual issuer.
        List<CommentThread> roots = List.of(new CommentThread(account, http, 4L, "video", forgedOpening),
            new CommentThread(account, http, 4L, "video", forgedOpening, issuer));
        Comment presentation = CommentPageKt.commentPage("{\"commentRenderer\":{\"commentId\":\"fixture\",\"contentText\":{\"simpleText\":\"Fixture\"}}}").getComments().get(0);
        for (CommentThread forged : roots) {
            CommentHandle handle = new CommentHandle(forged, 1L, new DiscussionComment(presentation));
            DiscussionRoute route = new DiscussionRoute("https://www.youtube.com/youtubei/v1/next", "caller-token");
            rejected(forged.load(), "unissued_comment_thread");
            rejected(forged.create("Never sent"), "unissued_comment_thread");
            rejected(forged.next(new CommentCursor(forged, 0L, route, false)), "unissued_comment_thread");
            rejected(forged.sort(new CommentSortOrder(forged, 0L, route, "Display", false)), "unissued_comment_thread");
            rejected(forged.replies(handle), "unissued_comment_thread");
            rejected(forged.reply(handle, "Never sent"), "unissued_comment_thread");
            rejected(forged.rate(handle, CommentVote.LIKE), "unissued_comment_thread");
            rejected(forged.delete(handle), "unissued_comment_thread");
            CommentPermissions permissions = handle.getPermissions();
            assertFalse(permissions.getCanReply()); assertFalse(permissions.getCanDelete());
            assertFalse(permissions.getCanLoadReplies()); assertFalse(permissions.getCanResolveActions());
            assertFalse(permissions.getCanRate()); assertTrue(permissions.getVotes().isEmpty());
        }
        assertEquals(1, reads.get()); assertEquals(1, writes.get());
        assertTrue(issued.load().getValue().getCanCreate()); // Invalid roots do not revoke a real grant.
        account.clear();
        assertEquals(RequestFailure.CANCELLED, issued.create("Never sent").getError().getKind());
        assertEquals(1, writes.get());
    }

    @Test public void reconstructedChannelRootsCannotUseCallerSuppliedRoutesOrCursors() {
        String id = "UC_x5XG1OV2P6uZZ5FSM9Ttw";
        AccountSession account = new AccountSession();
        AtomicInteger reads = new AtomicInteger();
        HttpClient http = request -> {
            reads.incrementAndGet();
            return reply("{\"metadata\":{\"channelMetadataRenderer\":{\"externalId\":\"" + id + "\",\"title\":\"Fixture\"}},"
                + "\"contents\":{\"twoColumnBrowseResultsRenderer\":{\"tabs\":[{\"tabRenderer\":{\"tabIdentifier\":\"videos\",\"selected\":true}}]}}}");
        };
        ChannelSession issuer = new ChannelSession(account, http);
        ChannelHandle issued = issuer.open(id).getValue();
        assertTrue(issued.openTab(ChannelTabKind.VIDEOS).getOk());
        assertEquals(1, reads.get());
        PostPage emptyPosts = new PostPage(Collections.emptyList(), null, null);
        for (ChannelHandle forged : List.of(new ChannelHandle(account, 0L, http, issued.getChannel(), emptyPosts),
            new ChannelHandle(account, 0L, http, issued.getChannel(), emptyPosts, issuer))) {
            rejected(forged.openTab(ChannelTabKind.VIDEOS), "unissued_channel_handle");
            rejected(forged.openPosts(), "unissued_channel_handle");
            assertEquals(RequestFailure.INVALID, forged.next(new ChannelTabCursor(forged, ChannelTabKind.VIDEOS, "caller-token")).getError().getKind());
            assertEquals(RequestFailure.INVALID, forged.nextPosts(new ChannelPostsCursor(forged, "caller-token")).getError().getKind());
        }
        assertEquals(1, reads.get());
        assertTrue(issued.openTab(ChannelTabKind.VIDEOS).getOk());
        account.clear();
        assertEquals(RequestFailure.CANCELLED, issued.openTab(ChannelTabKind.VIDEOS).getError().getKind());
        assertEquals(1, reads.get());
    }
}
