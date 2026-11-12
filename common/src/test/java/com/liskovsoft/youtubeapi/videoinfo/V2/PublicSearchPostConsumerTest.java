package com.liskovsoft.youtubeapi.videoinfo.V2;

import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import static org.junit.Assert.*;

/** App-facing Java consumption of a native post, author data and the shelf's original CTA. */
public class PublicSearchPostConsumerTest {
    @Test public void nativePostAndCtaCanBeRenderedUsingNewGettersAndBothFullConstructorShapes() {
        String author = "UCaaaaaaaaaaaaaaaaaaaaaa";
        String body = "{\"contents\":{\"twoColumnSearchResultsRenderer\":{\"primaryContents\":{\"sectionListRenderer\":{\"contents\":["
            + "{\"itemSectionRenderer\":{\"contents\":[{\"shelfRenderer\":{\"title\":{\"simpleText\":\"Posts\"},\"content\":{\"horizontalListRenderer\":{\"items\":["
            + "{\"postRenderer\":{\"postId\":\"Ugkx_java_post\",\"contentText\":{\"simpleText\":\"Native body\"},\"authorText\":{\"simpleText\":\"Author\"},"
            + "\"authorEndpoint\":{\"browseEndpoint\":{\"browseId\":\"" + author + "\"}},\"authorThumbnail\":{\"thumbnails\":[{\"url\":\"https://yt3.example/avatar\",\"width\":88,\"height\":88}]} }},"
            + "{\"buttonCardViewModel\":{\"title\":\"Native posts tab\",\"rendererContext\":{\"commandContext\":{\"onTap\":{\"innertubeCommand\":{"
            + "\"commandMetadata\":{\"webCommandMetadata\":{\"apiUrl\":\"/youtubei/v1/browse\"}},\"browseEndpoint\":{\"browseId\":\"" + author + "\",\"params\":\"issued-tab\"}}}}}}}"
            + "]}}}}]}}]}}}}}";
        AtomicInteger requests = new AtomicInteger();
        HttpClient http = request -> { requests.incrementAndGet(); return new HttpReply(200, body, Collections.emptyMap()); };
        CatalogPage page = new CatalogSession(new AccountSession(), http, 0).search("fixture").getValue();
        assertNotNull(page);
        PreparedMediaItem prepared = page.getItems().get(0);
        MediaItem card = prepared.getItem();
        PostItem post = card.getPost();
        assertEquals("post", card.getKind());
        assertNotNull(post);
        assertEquals("Native body", post.getText());
        assertEquals("https://www.youtube.com/post/Ugkx_java_post", post.getPermalink());
        assertEquals(author, prepared.getAuthor().getChannelId());
        assertNotNull(prepared.getAuthor().getAvatar());
        assertNull(card.getThumbnail());
        assertEquals("Native posts tab", page.getActions().get(0).getTitle());
        assertEquals(page.getActions(), page.getGroups().get(0).getActions());
        CatalogGroup group = page.getGroups().get(0);
        CatalogGroup previousGroupShape = new CatalogGroup(group.getTitle(), group.getItems());
        assertTrue(previousGroupShape.getActions().isEmpty());
        CatalogGroup completeGroupShape = new CatalogGroup(group.getTitle(), group.getItems(), group.getActions());
        assertEquals(group.getActions(), completeGroupShape.getActions());

        MediaItem full = new MediaItem(card.getKind(), card.getId(), card.getTitle(), card.getAuthor(), card.getThumbnail(),
            card.getDuration(), card.getChannelId(), card.getPlaylistId(), card.getParams(), card.getEndpoint(), card.getActions(),
            card.getStartSeconds(), card.getProgressPercent(), card.getLive(), card.getPlayerParams(), card.getAuthorThumbnail(),
            card.getViewCount(), card.getPublished(), card.getVideoCountText(), card.getMenuEndpoint(), card.getHasNewContent(),
            card.getAuthorArtwork(), post);
        assertSame(post, full.getPost());
        MediaItem old = new MediaItem(card.getKind(), card.getId(), card.getTitle(), card.getAuthor(), card.getThumbnail(),
            card.getDuration(), card.getChannelId(), card.getPlaylistId(), card.getParams(), card.getEndpoint(), card.getActions(),
            card.getStartSeconds(), card.getProgressPercent(), card.getLive(), card.getPlayerParams(), card.getAuthorThumbnail(),
            card.getViewCount(), card.getPublished(), card.getVideoCountText(), card.getMenuEndpoint(), card.getHasNewContent(),
            card.getAuthorArtwork());
        assertNull(old.getPost());
        assertEquals(card.getId(), old.getId());
        assertEquals(1, requests.get());
    }

    @Test public void invalidCallerConstructedPostHasNoPublicDestination() {
        PostItem invalid = new PostItem(null, "bad id", null, null, null, null, Collections.emptyList(), null, null,
            null, null, null, null, null, null, false);
        assertNull(invalid.getPermalink());
    }
}
