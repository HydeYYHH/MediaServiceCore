package com.liskovsoft.youtubeapi.videoinfo.V2

import org.junit.Assert.*
import org.junit.Test

class NoticeDestinationTest {
    private val video = "dQw4w9WgXcQ"
    private val channel = "UC_x5XG1OV2P6uZZ5FSM9Ttw"
    private val post = "UgkxFixture_Post123"

    @Test fun anInboxKeepsItsDeclaredTargetsAndSeparateAuthorArtwork() {
        val endpoints = listOf(
            """{"watchEndpoint":{"videoId":"$video","startTimeSeconds":42}}""",
            """{"reelWatchEndpoint":{"videoId":"$video"}}""",
            """{"browseEndpoint":{"browseId":"$channel"}}""",
            """{"watchPlaylistEndpoint":{"playlistId":"PLfixture123"}}""",
            """{"browseEndpoint":{"browseId":"VLPLfixture456"}}""",
            postEndpoint("/post/$post", channel),
            postEndpoint("https://www.youtube.com/post/$post", "FEposts")
        )
        var calls = 0
        val reply = loadNotices(RequestContext(webSession = WebSession("SAPISID=fixture")), http = object : HttpClient {
            override fun execute(request: HttpRequest): HttpReply {
                calls++
                assertTrue(request.url.contains("/notification/get_notification_menu"))
                return HttpReply(200, inbox(endpoints))
            }
        })
        val result = reply.value!!.notices
        assertEquals(1, calls)
        assertEquals(listOf(
            NoticeDestination.Video(video, 42), NoticeDestination.Short(video), NoticeDestination.Channel(channel),
            NoticeDestination.Playlist("PLfixture123"), NoticeDestination.Playlist("PLfixture456"),
            NoticeDestination.Post(post, channel), NoticeDestination.Post(post, null)
        ), result.map { it.destination })
        assertEquals(listOf(video, video, null, null, null, null, null), result.map { it.videoId })
        assertTrue(result.all { it.avatar == "https://yt3.ggpht.com/fixture-avatar" })
        assertTrue(result.all { it.thumbnail == "https://i.ytimg.com/fixture-video" })
        assertTrue(result.all { it.read == false && it.readCommand != null && it.hideKey == "fixture-hide" })
    }

    @Test fun onlyTheOwningCommandAndItsConsistentMetadataCanDeclareTheTarget() {
        assertEquals(NoticeDestination.Post(post, channel), destination(
            """{"commandMetadata":{"webCommandMetadata":{"url":"/post/$post","webPageType":"WEB_PAGE_TYPE_POST_DETAIL"}},"innertubeCommand":{"browseEndpoint":{"browseId":"$channel"}}}"""))
        assertEquals(NoticeDestination.Video(video), destination(
            """{"commandMetadata":{"webCommandMetadata":{"url":"/watch?v=$video","webPageType":"WEB_PAGE_TYPE_WATCH"}},"watchEndpoint":{"videoId":"$video"}}"""))
        listOf(
            """{"watchEndpoint":{"videoId":"$video"},"browseEndpoint":{"browseId":"$channel"}}""",
            """{"innertubeCommand":{"watchEndpoint":{"videoId":"$video"}},"browseEndpoint":{"browseId":"$channel"}}""",
            """{"commandMetadata":{"webCommandMetadata":{"webPageType":"WEB_PAGE_TYPE_CHANNEL"}},"innertubeCommand":{"commandMetadata":{"webCommandMetadata":{"webPageType":"WEB_PAGE_TYPE_WATCH"}},"watchEndpoint":{"videoId":"$video"}}}""",
            """{"commandMetadata":{"webCommandMetadata":{"url":"/watch?v=aaaaaaaaaaa"}},"watchEndpoint":{"videoId":"$video"}}""",
            """{"commandMetadata":{"webCommandMetadata":{"url":"/post/$post"}},"browseEndpoint":{"browseId":"$channel"}}""",
            """{"commandMetadata":{"webCommandMetadata":{"url":"/post/$post","webPageType":"WEB_PAGE_TYPE_POST_DETAIL"}}}""",
            """{"urlEndpoint":{"url":"https://www.youtube.com/post/$post"}}""",
            """{"watchEndpoint":{"videoId":"invalid"}}""",
            """{"watchEndpoint":{"videoId":"$video","startTimeSeconds":-1}}"""
        ).forEach { assertNull(destination(it)) }
    }

    @Test fun aPostRequiresItsExplicitPageTypeAndAnExactFirstPartyRoute() {
        listOf(
            "https://example.com/post/$post", "https://www.youtube.com.example.com/post/$post",
            "https://user@www.youtube.com/post/$post", "https://www.youtube.com:444/post/$post",
            "http://www.youtube.com/post/$post", "//www.youtube.com/post/$post",
            "/watch?v=$video", "/post/$post/extra", "/post/%55gkxFixture_Post123", "/post/$post#fragment"
        ).forEach { assertNull(destination(postEndpoint(it, channel))) }
        assertNull(destination("""{"browseEndpoint":{"browseId":"$channel"},"commandMetadata":{"webCommandMetadata":{"url":"/post/$post","webPageType":"WEB_PAGE_TYPE_CHANNEL"}}}"""))
    }

    @Test fun thePreviousJavaConstructorStillConstructsAnOrdinaryVideoNotice() {
        val constructor = Notice::class.java.getConstructor(
            String::class.java, String::class.java, String::class.java, String::class.java,
            String::class.java, String::class.java, String::class.java, String::class.java,
            Boolean::class.javaObjectType, NoticeCommand::class.java
        )
        val notice = constructor.newInstance(video, "Fixture", null, null, null, null, null, null, false, null)
        assertEquals(NoticeDestination.Video(video), notice.destination)
    }

    private fun postEndpoint(url: String, browseId: String) = """{"commandMetadata":{"webCommandMetadata":{"url":"$url","webPageType":"WEB_PAGE_TYPE_POST_DETAIL"}},"browseEndpoint":{"browseId":"$browseId"}}"""
    private fun destination(endpoint: String) = notices(inbox(listOf(endpoint))).single().destination
    private fun inbox(endpoints: List<String>): String {
        val items = endpoints.mapIndexed { index, endpoint ->
            """{"notificationRenderer":{"notificationId":"fixture-$index","shortMessage":{"simpleText":"Fixture"},"read":false,"thumbnail":{"thumbnails":[{"url":"https://yt3.ggpht.com/fixture-avatar"}]},"videoThumbnail":{"thumbnails":[{"url":"https://i.ytimg.com/fixture-video"}]},"navigationEndpoint":$endpoint,"recordClickEndpoint":{"recordNotificationInteractionsEndpoint":{"serializedInteractionsRequest":"fixture-read"}},"contextualMenu":{"recordNotificationInteractionsEndpoint":{"serializedInteractionsRequest":"fixture-hide"}}}}"""
        }.joinToString(",")
        return """{"sections":[{"notificationSectionRenderer":{"items":[$items]}}]}"""
    }
}
