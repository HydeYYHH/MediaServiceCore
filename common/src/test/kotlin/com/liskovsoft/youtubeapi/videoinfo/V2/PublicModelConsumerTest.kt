package com.liskovsoft.youtubeapi.videoinfo.V2

import org.junit.Assert.*
import org.junit.Test

/** Models are consumed as a host's download sheet, skip controls and optional metadata panels would use them. */
class PublicModelConsumerTest {
    private fun queryParameter(url: String, name: String): String? = java.net.URI(url).rawQuery
        ?.split('&')?.firstOrNull { it.substringBefore('=') == name }?.substringAfter('=')
        ?.let { java.net.URLDecoder.decode(it, "UTF-8") }
    @Test fun downloadSheetUsesParsedDimensionsRangesAndAudioProperties() {
        val answer = playerAnswer("""{
          "playabilityStatus":{"status":"OK"},
          "videoDetails":{"videoId":"download","title":"Lesson","lengthSeconds":"90","isLive":false},
          "streamingData":{"expiresInSeconds":"60","adaptiveFormats":[
            {"itag":137,"mimeType":"video/mp4; codecs=\"avc1.640028\"","url":"https://media.example/video",
             "width":1920,"height":1080,"bitrate":2000000,"fps":30,"contentLength":"1024",
             "initRange":{"start":"0","end":"99"},"indexRange":{"start":"100","end":"199"},
             "approxDurationMs":"90000","qualityLabel":"1080p","colorInfo":{"transferCharacteristics":"COLOR_TRANSFER_CHARACTERISTICS_BT709"}},
            {"itag":140,"mimeType":"audio/mp4; codecs=\"mp4a.40.2\"","url":"https://media.example/audio",
             "audioSampleRate":"44100","audioChannels":2,"approxDurationMs":"90000",
             "audioTrack":{"id":"es.1","displayName":"Spanish","audioIsDefault":false,"isAutoDubbed":false}}
          ]}}
        """)
        val video = answer.adaptiveFormats.single { it.mimeType!!.startsWith("video/") }
        val audio = answer.adaptiveFormats.single { it.mimeType!!.startsWith("audio/") }
        val downloadLabel = "${video.width}×${video.height} · ${video.qualityLabel} · ${video.fps}fps"
        assertEquals("1920×1080 · 1080p · 30fps", downloadLabel)
        assertEquals(90_000L, video.approxDurationMs)
        assertEquals(1024L, video.contentLength)
        assertEquals("bytes=100-199", video.indexRange!!.let { "bytes=${it.start}-${it.end}" })
        assertEquals(0L, video.initRange!!.start)
        assertEquals(99L, video.initRange!!.end)
        assertEquals(44_100, audio.sampleRate)
        assertEquals(2, audio.audioChannels)
        assertEquals(90_000L, audio.approxDurationMs)
        val track = requireNotNull(audio.audioTrack)
        assertEquals("Spanish", track.name)
        assertFalse(track.autoDubbed)
        assertEquals(listOf("avc1.640028"), video.codecs)
        assertEquals(VideoRange.SDR, video.dynamicRange)
        assertEquals(60L, answer.expiresInSeconds)
        assertFalse(answer.transportFailed)
        assertNull(answer.reason)
        assertTrue(answer.hasDirectUrl)
        assertFalse(answer.hasMuxed)
        assertFalse(answer.hasHls)
        assertFalse(answer.live)
        assertEquals("download", answer.details!!.videoId)
        val selected = selectPlayback(answer, PlaybackPreference(maxHeight = 1080, audioLanguage = "es",
            videoCodecs = setOf("avc1"), audioCodecs = setOf("mp4a"), dynamicRange = VideoRange.SDR))
        assertEquals(video.url, selected.videoUrl)
        assertEquals(audio.url, selected.audioUrl)
    }

    @Test fun skipControlsUseOnlyActionableSegmentsAndKeepTheirStableIds() {
        val parsed = sponsorSegments("""[
          {"UUID":"sponsor-1","segment":[1.25,4.5],"category":"sponsor","actionType":"skip"},
          {"UUID":"intro-1","segment":[5,6],"category":"intro","actionType":"mute"},
          {"UUID":"bad","segment":[8,7],"category":"sponsor","actionType":"skip"}
        ]""")!!
        val skipped = parsed.filter { it.category == "sponsor" && it.actionType == "skip" }
        assertEquals(listOf("sponsor-1"), skipped.map { it.id })
        assertEquals(3.25, skipped.sumOf { it.endSeconds - it.startSeconds }, 0.0)
        assertEquals(2, parsed.size)
        assertEquals(4.5, skipped.single().endSeconds, 0.0)
    }

    @Test fun dislikePanelHidesDeletedRatingsAndDisplaysCompleteLiveCounts() {
        val visible = dislikeData("""{"id":"v","likes":300,"dislikes":20,"viewCount":10000,"rating":4.5,"deleted":false}""")!!
        fun panel(data: DislikeData): String? = if (data.deleted) null else
            "${data.likes}/${data.dislikes} · ${data.views} views · ${data.rating} stars"
        assertEquals("v", visible.videoId)
        assertEquals("300/20 · 10000 views · 4.5 stars", panel(visible))
        val removed = dislikeData("""{"id":"v","deleted":true}""")!!
        assertNull(panel(removed))
    }


    @Test fun hostQueuePanelUsesMetadataAndRendersOnlyTheSelectedBrowseTabAsActive() {
        val browse = browsePage("""{"contents":[
          {"tabRenderer":{"title":"Videos","selected":true,"endpoint":{"browseEndpoint":{"browseId":"UCcourse"}}}},
          {"tabRenderer":{"title":"Posts","selected":false,"endpoint":{"browseEndpoint":{"browseId":"UCcourse","params":"posts"}}}}
        ]}""")
        assertEquals(listOf("Videos"), browse.tabs.filter { it.selected }.map { it.title })
        val page = watchPage("""{"contents":{"twoColumnWatchNextResults":{"playlist":{"playlist":{
          "playlistId":"PL_course","title":{"simpleText":"Course"},"totalVideos":"2","isInfinite":false,"currentIndex":0,
          "contents":[
            {"playlistPanelVideoRenderer":{"videoId":"lesson1","title":{"simpleText":"First lesson"},"selected":true,
              "thumbnail":{"thumbnails":[{"url":"https://img.example/first.jpg"}]},
              "navigationEndpoint":{"watchEndpoint":{"videoId":"lesson1","playlistId":"PL_course","index":0}}}},
            {"playlistPanelVideoRenderer":{"videoId":"lesson2","title":{"simpleText":"Second lesson"},"selected":false,
              "thumbnail":{"thumbnails":[{"url":"https://img.example/second.jpg"}]},
              "navigationEndpoint":{"watchEndpoint":{"videoId":"lesson2","playlistId":"PL_course","index":1}}}},
            {"continuationItemRenderer":{"continuationEndpoint":{"continuationCommand":{"token":"queue-next"}}}}
          ]}}}}}""")
        val queue = requireNotNull(page.queue)
        assertEquals("PL_course", queue.playlistId)
        assertEquals("Course", queue.title)
        assertEquals(2L, queue.totalVideos)
        assertFalse(queue.infinite)
        assertEquals("queue-next", queue.nextKey)
        val selected = queue.videos.single { it.selected }
        assertEquals("First lesson", selected.title)
        assertEquals("https://img.example/first.jpg", selected.thumbnail)
        assertEquals(listOf("First lesson", "Second lesson"), queue.videos.map { it.title })
    }

    @Test fun subtitleMenuKeepsParsedStableTrackIdsAndHumanReadableNames() {
        val tracks = playerCaptions("""{"captions":{"playerCaptionsTracklistRenderer":{"captionTracks":[
          {"baseUrl":"https://captions.example/en","languageCode":"en","name":{"simpleText":"English"},"vssId":".en"},
          {"baseUrl":"https://captions.example/es","languageCode":"es","name":{"simpleText":"Spanish"},"vssId":".es"}
        ]}}}""")
        val menu = tracks.associate { it.id to it.name }
        assertEquals(mapOf(".en" to "English", ".es" to "Spanish"), menu)
        assertEquals("https://captions.example/es", tracks.single { it.id == ".es" }.url)
    }

    @Test fun seekPreviewUsesTheParsedSheetTemplateAndExactTileDimensions() {
        val template = "https://img.example/\$L/\$N.jpg"
        val board = requireNotNull(readStoryboard("$template|160#90#100#5#5#1000#sheet\$M#signature"))
        assertEquals(template, board.template)
        assertEquals(1, board.levels.size)
        val grid = board.levels.single()
        assertEquals(160, grid.width)
        assertEquals(90, grid.height)
        val tile = requireNotNull(board.tile(27_000, 100_000))
        assertEquals(160, tile.width)
        assertEquals(90, tile.height)
        assertEquals(2, tile.column)
        assertEquals(0, tile.row)
        assertTrue(tile.url.startsWith("https://img.example/0/sheet1.jpg?"))
        assertEquals("signature", queryParameter(tile.url, "sigh"))
    }

    @Test fun sabrHostReceivesTheMatchingStreamerConfigAndPlaybackForm() {
        val http = object : HttpClient {
            override fun execute(request: HttpRequest) = HttpReply(200, """{
              "playabilityStatus":{"status":"OK"},
              "streamingData":{"serverAbrStreamingUrl":"https://media.example/sabr"},
              "playerConfig":{"mediaCommonConfig":{"mediaUstreamerRequestConfig":{"videoPlaybackUstreamerConfig":"streamer-config"}}}
            }""")
        }
        val loaded = PlaybackSession(http).load("v", includeMuxedFloor = false)
        assertTrue(loaded.ok)
        val result = requireNotNull(loaded.value)
        assertEquals(PlaybackForm.VOD, result.form)
        assertEquals("https://media.example/sabr", result.playback.sabrUrl)
        assertEquals("streamer-config", result.playback.ustreamerConfig)
    }

    @Test fun rssFallbackCardPreservesPresentationAndChannelMetadata() {
        val parsed = rssVideos("""<feed xmlns="http://www.w3.org/2005/Atom"
            xmlns:yt="http://www.youtube.com/xml/schemas/2015" xmlns:media="http://search.yahoo.com/mrss/">
          <entry><yt:videoId>lesson1</yt:videoId><yt:channelId>UCcourse</yt:channelId>
            <title>First lesson</title><author><name>Teacher</name></author>
            <published>2026-11-01T12:00:00Z</published><media:thumbnail url="https://img.example/rss.jpg"/>
          </entry></feed>""")
        assertTrue(parsed.ok)
        val video = requireNotNull(parsed.value).single()
        val titleLine = video.title + " · " + video.author
        assertEquals("First lesson · Teacher", titleLine)
        assertEquals("lesson1", video.videoId)
        assertEquals("UCcourse", video.channelId)
        assertEquals("https://img.example/rss.jpg", video.thumbnail)
        assertEquals("2026-11-01T12:00:00Z", video.published)
    }

    @Test fun brandingPanelRespectsOriginalFlagsAndUsesTheSelectedThumbnailTimestamp() {
        val data = deArrowData("""{
                  "titles":[{"title":"Original title","votes":-2,"original":true},
                            {"title":"Clear explanation","votes":3,"locked":true,"original":false}],
                  "thumbnails":[{"timestamp":12.5,"votes":2,"locked":false,"original":false}],
                  "videoDuration":90,"randomTime":0.2
                }""")!!
        assertTrue(data.titles.first().original)
        val chosen = data.preferredTitle()!!
        assertFalse(chosen.original)
        assertEquals("Clear explanation", chosen.title)
        assertTrue(chosen.locked)
        assertEquals(3.0, chosen.votes, 0.0)
        assertEquals(1, data.thumbnails.size)
        assertFalse(data.thumbnails.single().original)
        assertEquals(12.5, data.thumbnails.single().timestamp!!, 0.0)
        assertEquals("12.5", queryParameter(deArrowThumbnailUrl("v", data)!!, "time"))
        assertEquals(90.0, data.durationSeconds!!, 0.0)
        assertEquals(0.2, data.randomTime!!, 0.0)
    }
}
