package com.liskovsoft.youtubeapi.videoinfo.V2;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import static org.junit.Assert.*;

public class PublicPlaylistMembershipConsumerTest {
    @Test public void javaConstructorEntriesExposeSaveChoicesWithoutGuessingAnUnknownFlag() {
        PlaylistMembershipSession defaults = new PlaylistMembershipSession(new AccountSession());
        assertEquals(RequestFailure.AUTH, defaults.open("jNQXAC9IVRw").getError().getKind());
        AccountSession account = new AccountSession(new RequestContext("owner", null, "en", "US", 2L, null, new WebSession("SAPISID=fixture", 0), null));
        AtomicInteger calls = new AtomicInteger();
        HttpClient response = request -> { calls.incrementAndGet(); return new HttpReply(200, "{\"contents\":[{\"addToPlaylistRenderer\":{\"playlists\":[{\"playlistAddToOptionRenderer\":{\"playlistId\":\"PLlesson\",\"title\":{\"simpleText\":\"Lessons\"},\"containsSelectedVideos\":\"SOME\",\"canEdit\":true}}]}}]}", Collections.emptyMap()); };
        PlaylistMembershipSession injected = new PlaylistMembershipSession(account, response);
        ReadResult<PlaylistMembershipDirectory> result = injected.open("jNQXAC9IVRw");
        assertTrue(result.getOk());
        assertNull(result.getError());
        PlaylistMembershipEntry entry = result.getValue().getEntries().get(0);
        assertEquals("PLlesson", entry.getPlaylistId());
        assertEquals("Lessons", entry.getTitle());
        assertEquals(PlaylistPresence.UNKNOWN, entry.getPresence());
        assertTrue(entry.getEditable());
        PlaylistMembershipDirectory directory = result.getValue();
        PlaylistMembershipEntry forged = new PlaylistMembershipEntry(directory, "PLoutside", "Not discovered", PlaylistPresence.ABSENT, true);
        assertEquals("foreign_playlist_membership_entry", directory.setContains(forged, true).getError().getCode());
        assertEquals(1, calls.get());
    }

    @Test public void javaCannotManufactureAnIssuedRootDirectoryByCopyingTheRealIssuer() {
        AccountSession account = new AccountSession(new RequestContext("owner", null, "en", "US", 2L, null,
            new WebSession("SAPISID=fixture", 0), null));
        AtomicInteger calls = new AtomicInteger();
        HttpClient response = request -> {
            calls.incrementAndGet();
            return new HttpReply(200, "{\"contents\":[{\"addToPlaylistRenderer\":{\"playlists\":[]}}]}", Collections.emptyMap());
        };
        PlaylistMembershipSession issuer = new PlaylistMembershipSession(account, response);
        assertTrue(issuer.open("jNQXAC9IVRw").getOk());
        assertEquals(1, calls.get());
        List<MembershipChoice> choices = Collections.singletonList(new MembershipChoice(
            new PlaylistChoice("PLnot-issued", "Not discovered", false, false), true));
        PlaylistMembershipDirectory withoutIssuer = new PlaylistMembershipDirectory(account, 2L, response,
            "jNQXAC9IVRw", choices);
        PlaylistMembershipDirectory copiedIssuer = new PlaylistMembershipDirectory(account, 2L, response,
            "jNQXAC9IVRw", choices, issuer);
        for (PlaylistMembershipDirectory forged : List.of(withoutIssuer, copiedIssuer)) {
            ReadResult<PlaylistMembershipUpdate> result = forged.setContains(forged.getEntries().get(0), true);
            assertEquals(RequestFailure.INVALID, result.getError().getKind());
            assertEquals("unissued_playlist_membership_directory", result.getError().getCode());
        }
        assertEquals(1, calls.get()); // Both forged roots were rejected before any read or edit.
    }
}
