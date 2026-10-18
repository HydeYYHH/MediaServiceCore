package com.liskovsoft.youtubeapi.videoinfo.V2
import org.junit.Assert.*
import org.junit.Test
class PlayerChallengesTest {
    @Test fun doesNotAcceptAnUnsolvedThrottleUrl() {
        val http=object:HttpClient{override fun execute(request:HttpRequest)=HttpReply(200,"{\"streamingData\":{\"formats\":[{\"url\":\"https://media?n=raw\"}]}}")}
        assertFalse(loadPlayer("v",RequestContext(),http).ok)
        val solver=object:PlayerSolver{override fun solve(request:PlayerChallengeRequest)=PlayerChallenge(emptyMap(),mapOf("raw" to "solved"))}
        assertTrue(loadPlayer("v",RequestContext(),http,PlayerScript("https://script","source"),solver).ok)
    }
    @Test fun cachesByScriptContentAndTokenPurposeWithoutNullIdentityCollisions() {
        var solves=0;var tokens=0;var now=0L
        val solver=CachedPlayerSolver(object:PlayerSolver{override fun solve(request:PlayerChallengeRequest):PlayerChallenge{solves++;return PlayerChallenge(mapOf("s" to "sig"),emptyMap())}})
        val request=PlayerChallengeRequest(PlayerScript("url","one"),setOf("s"),emptySet())
        solver.solve(request);solver.solve(request);solver.solve(request.copy(script=PlayerScript("url","two")));assertEquals(2,solves)
        val provider=CachedPlayerTokens(object:PlayerTokenProvider{override fun token(request:PlayerTokenRequest):PlayerToken{tokens++;return PlayerToken("value",now+10)}},now={now})
        val r=PlayerTokenRequest(PlayerClient.TV,"v",RequestContext(),TokenPurpose.PLAYER)
        provider.token(r);provider.token(r);provider.token(r.copy(purpose=TokenPurpose.MEDIA));assertEquals(2,tokens)
        now=10;provider.token(r);assertEquals(3,tokens)
        assertNotEquals(RequestContext().scope(),RequestContext(accountId="null").scope())
    }
}
