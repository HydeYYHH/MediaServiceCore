package com.liskovsoft.youtubeapi.videoinfo.V2

import org.junit.Assert.*
import org.junit.Test

class PlaylistOwnerTest {
    private val owner = "UCaaaaaaaaaaaaaaaaaaaaaa"
    private val other = "UCbbbbbbbbbbbbbbbbbbbbbb"
    private fun endpoint(id: String) = """{"browseEndpoint":{"browseId":"$id"}}"""
    private fun page(header: String, sidebar: String = "{}") = """{
      "header":$header,"sidebar":$sidebar,
      "contents":{"twoColumnBrowseResultsRenderer":{"tabs":[{"tabRenderer":{"content":{"sectionListRenderer":{"contents":[
        {"itemSectionRenderer":{"contents":[{"playlistVideoListRenderer":{"contents":[
          {"playlistVideoRenderer":{"videoId":"abcdefghijk","title":{"simpleText":"Video"},
            "shortBylineText":{"runs":[{"text":"Video author","navigationEndpoint":${endpoint(other)}}]}}}
        ]}}]}}
      ]}}}}]}}
    }"""
    private fun sidebar(id: String) = """{"playlistSidebarRenderer":{"items":[
      {"playlistSidebarSecondaryInfoRenderer":{"videoOwner":{"videoOwnerRenderer":{
        "title":{"runs":[{"text":"Owner","navigationEndpoint":${endpoint(id)}}]},
        "navigationEndpoint":${endpoint(id)},
        "thumbnail":{"thumbnails":[{"url":"https://yt3.ggpht.com/owner-avatar","width":88,"height":88}]}
      }}}}
    ]}}"""

    @Test fun listOwnerEndpointAndSidebarArtworkArePreparedWithoutReadingTheVideoAuthor() {
        val result = playlistPage(page("""{"playlistHeaderRenderer":{
          "title":{"simpleText":"Playlist"},"ownerText":{"simpleText":"Owner"},"ownerEndpoint":${endpoint(owner)}
        }}""", sidebar(owner)))
        assertEquals("Playlist", result.title)
        assertEquals(listOf("abcdefghijk"), result.videos.map { it.videoId })
        assertEquals("Video author", result.videos.single().author)
        assertEquals(owner, result.ownerInfo?.channelId)
        assertEquals("Owner", result.ownerInfo?.name)
        assertEquals("https://yt3.ggpht.com/owner-avatar", result.ownerInfo?.avatar?.select(24, 24)?.url)
    }

    @Test fun modernAttributedOwnerUsesItsExactCommandAndSingleAvatarInsteadOfTheCollaboratorPanel() {
        val result = playlistPage(page("""{"pageHeaderRenderer":{"pageTitle":"Playlist","content":{"pageHeaderViewModel":{
          "attribution":{"avatarStackViewModel":{
            "text":{"content":"Owner • 2 videos","commandRuns":[{"startIndex":0,"length":5,"onTap":{"innertubeCommand":${endpoint(owner)}}}]},
            "avatars":[{"avatarViewModel":{"image":{"sources":[{"url":"https://yt3.ggpht.com/modern-avatar","width":48,"height":48}]}}}],
            "rendererContext":{"commandContext":{"onTap":{"innertubeCommand":{"showEngagementPanelEndpoint":{"identifier":{"tag":"collaborators"}}}}}}
          }}
        }}}}"""))
        assertEquals(owner, result.ownerInfo?.channelId)
        assertEquals("Owner", result.ownerInfo?.name)
        assertEquals("https://yt3.ggpht.com/modern-avatar", result.ownerInfo?.avatar?.best?.url)
    }

    @Test fun missingOrConflictingOwnersRemainUnavailableWithoutLosingThePlaylist() {
        val missing = playlistPage(page("""{"playlistHeaderRenderer":{"title":{"simpleText":"Playlist"},"ownerText":{"simpleText":"Owner"}}}"""))
        assertEquals(1, missing.videos.size)
        assertNull(missing.ownerInfo?.channelId)
        assertNull(missing.ownerInfo?.avatar)
        val conflicting = playlistPage(page("""{"playlistHeaderRenderer":{
          "title":{"simpleText":"Playlist"},"ownerEndpoint":${endpoint(owner)}
        }}""", sidebar(other)))
        assertEquals(1, conflicting.videos.size)
        assertNull(conflicting.ownerInfo?.channelId)
        assertNull(conflicting.ownerInfo?.avatar)
    }

    @Test fun mixedCommandsCannotTurnAnOwnerLabelIntoNavigationAndTheJavaConstructorRemainsAvailable() {
        val mixed = endpoint(owner).dropLast(1) + """, "watchEndpoint":{"videoId":"abcdefghijk"}}"""
        val result = playlistPage(page("""{"playlistHeaderRenderer":{"title":{"simpleText":"Playlist"},"ownerEndpoint":$mixed}}"""))
        assertEquals(1, result.videos.size)
        assertNull(result.ownerInfo?.channelId)
        val legacy = PlaylistPage::class.java.getConstructor(String::class.java, String::class.java, List::class.java, String::class.java)
            .newInstance("Playlist", "Owner", emptyList<PlaylistVideo>(), null)
        assertNull(legacy.ownerInfo)
    }
}
