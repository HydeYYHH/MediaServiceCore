package com.liskovsoft.youtubeapi.videoinfo.V2
import java.util.LinkedHashMap

class PlaybackRecovery(private val session: PlaybackSession) {
    private data class Budget(var refreshed: Boolean = false, var fallback: Boolean = false, val refused: MutableSet<PlayerClient> = LinkedHashSet())
    private val budgets = LinkedHashMap<String, Budget>()
    @Synchronized fun recover(failed: PlaybackLoad, failedUrl: String, status: Int? = null, transportFailed: Boolean = false,
        preference: PlaybackPreference = failed.source?.preference ?: PlaybackPreference()): ReadResult<PlaybackLoad> {
        val source = session.source(failed) ?: return rejected("unknown_source")
        if (failedUrl !in source.urls) return rejected("unknown_request")
        if (status != 403 && !transportFailed) return rejected("not_recoverable")
        val key = source.videoId + "\u0000" + source.context.scope()
        val budget = budgets.getOrPut(key) { Budget() }
        while (budgets.size > 64) budgets.remove(budgets.keys.first())
        session.invalidate()
        if (!budget.refreshed) {
            budget.refreshed = true
            val refreshed = session.load(source.videoId, source.context, preference, fresh = true,
                excluded = PlayerClient.values().toSet() - source.client)
            if (refreshed.ok) return refreshed
            if (refreshed.error?.kind == RequestFailure.CANCELLED) return refreshed
        }
        budget.refused.add(source.client)
        if (budget.fallback) return rejected("recovery_exhausted")
        budget.fallback = true
        return session.load(source.videoId, source.context, preference, fresh = true, excluded = budget.refused)
    }
    @Synchronized fun reset(videoId: String, context: RequestContext) { budgets.remove(videoId + "\u0000" + context.scope()) }
    private fun rejected(code: String) = ReadResult<PlaybackLoad>(error = RequestError(RequestFailure.UNSUPPORTED, code))
}
