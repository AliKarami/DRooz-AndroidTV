package com.alikarami.drooz.data

import com.alikarami.drooz.site.model.TitleDetail
import com.alikarami.drooz.site.model.TitleRef
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit

/** Title details cache: TTL, in-flight dedupe, at most 2 concurrent fetches; failures are not cached. */
class TitleInfoRepository(
    private val api: SiteApi,
    private val now: () -> Long = System::currentTimeMillis,
    private val ttlMs: Long = 24L * 60 * 60 * 1000,
    fetchScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    // Supervisor child so one failed fetch cannot cancel the shared scope.
    private val scope = CoroutineScope(fetchScope.coroutineContext + SupervisorJob(fetchScope.coroutineContext[Job]))

    private class Entry(val detail: TitleDetail, val at: Long)

    private val mutex = Mutex()
    private val cache = HashMap<TitleRef, Entry>()
    private val inFlight = HashMap<TitleRef, Deferred<TitleDetail>>()
    private val limiter = Semaphore(2)
    private val played = MutableStateFlow<Map<TitleRef, String>>(emptyMap())

    /** Stream-page backdrop paths recorded after playback. */
    val playedBackdrops: StateFlow<Map<TitleRef, String>> = played

    suspend fun get(ref: TitleRef): TitleDetail {
        val job = mutex.withLock {
            fresh(ref)?.let { return it }
            inFlight.getOrPut(ref) {
                scope.async {
                    try {
                        val d = limiter.withPermit { api.title(ref) }
                        mutex.withLock { cache[ref] = Entry(d, now()) }
                        d
                    } finally {
                        mutex.withLock { inFlight.remove(ref) }
                    }
                }
            }
        }
        return job.await()
    }

    fun peek(ref: TitleRef): TitleDetail? = synchronized(cache) { fresh(ref) }

    fun remember(ref: TitleRef, backdropPath: String) = played.update { it + (ref to backdropPath) }

    private fun fresh(ref: TitleRef): TitleDetail? =
        cache[ref]?.takeIf { now() - it.at < ttlMs }?.detail
}
