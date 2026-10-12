package com.liskovsoft.youtubeapi.videoinfo.V2

data class PlayerLoad(
    val form: PlaybackForm,
    val client: PlayerClient?,
    val answer: PlayerAnswer?
)

fun loadPlayer(
    walk: PlayerAttempt,
    signIn: SignIn,
    videoId: String,
    visitor: String?,
    solve: ((Set<String>, Set<String>) -> PlayerChallenge)? = null,
    post: (PlayerQuery) -> Pair<Int, String>
): PlayerLoad {
    if (playerQuery(PlayerClient.WEB, videoId, visitor).url.isEmpty()) {
        return PlayerLoad(PlaybackForm.VOD, null, null)
    }
    var played: PlayerAnswer? = null
    val result = walk.run(signIn) { client ->
        val fetched = fetchPlayer(playerQuery(client, videoId, visitor), post)
        val answer = if (solve == null) fetched else openFormats(fetched, solve)
        played = answer
        answer
    }
    return PlayerLoad(result.form, result.client, if (result.client == null) null else played)
}
