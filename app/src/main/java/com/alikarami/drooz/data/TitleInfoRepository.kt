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
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Title details cache: TTL, in-flight dedupe, at most 2 concurrent fetches (plus one for [get]'s priority
 * callers); failures are not cached.
 * A play changes the page (played ✓, next episode): [invalidate] makes the next [get] refetch while
 * [lastKnown] still serves the old detail for an instant display. [clear] forgets everything at a
 * session change (AppViewModel).
 */
class TitleInfoRepository(
    private val api: SiteApi,
    private val now: () -> Long = System::currentTimeMillis,
    private val ttlMs: Long = 24L * 60 * 60 * 1000,
    fetchScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    // Supervisor child so one failed fetch cannot cancel the shared scope.
    private val scope = CoroutineScope(fetchScope.coroutineContext + SupervisorJob(fetchScope.coroutineContext[Job]))

    private class Entry(val detail: TitleDetail, val at: Long, val stale: Boolean = false)

    // Every critical section is non-suspending, so a plain lock (usable from non-suspend callers) is enough.
    private val lock = Any()
    // Concurrent: peek() is called from UI code while fetches write from IO threads.
    private val cache = java.util.concurrent.ConcurrentHashMap<TitleRef, Entry>()
    private val inFlight = HashMap<TitleRef, Deferred<TitleDetail>>()
    /** Bumped per title by [invalidate] and for all by [clear]: a fetch only caches if its stamp still holds. */
    private val versions = HashMap<TitleRef, Int>()
    private var epoch = 0
    private val limiter = Semaphore(2)
    /** Focus-driven hero fetches: one of their own, so they never wait behind a burst of background ones. */
    private val priorityLimiter = Semaphore(1)
    private val played = MutableStateFlow<Map<TitleRef, String>>(emptyMap())
    private val _invalidations = MutableStateFlow(0)
    private val _watchlistChanges = MutableStateFlow(0)

    /** Stream-page backdrop paths recorded after playback. */
    val playedBackdrops: StateFlow<Map<TitleRef, String>> = played

    /** Counts [invalidate] calls (plays): home revalidates «ادامه تماشا» when it changed. */
    val invalidations: StateFlow<Int> = _invalidations

    /** Counts «لیست من» toggles ([watchlistChanged]): a showing watchlist grid reloads when it changed. */
    val watchlistChanges: StateFlow<Int> = _watchlistChanges

    /**
     * [maxAgeMs]: a cached detail older than this is refetched (a page the user opens must show new
     * episodes; the TTL suits glances like the hero). [priority]: fetched on its own permit (the focused hero).
     */
    suspend fun get(ref: TitleRef, maxAgeMs: Long = ttlMs, priority: Boolean = false): TitleDetail {
        val job = synchronized(lock) {
            fresh(ref, maxAgeMs)?.let { return it }
            inFlight.getOrPut(ref) {
                val stamp = epoch to (versions[ref] ?: 0)
                val permits = if (priority) priorityLimiter else limiter
                scope.async {
                    val self = coroutineContext[Job]
                    try {
                        val d = permits.withPermit { api.title(ref) }
                        synchronized(lock) { if (stamp == epoch to (versions[ref] ?: 0)) cache[ref] = Entry(d, now()) }
                        d
                    } finally {
                        synchronized(lock) { if (inFlight[ref] === self) inFlight.remove(ref) }
                    }
                }
            }
        }
        return job.await()
    }

    /** A fresh, valid detail, or null. */
    fun peek(ref: TitleRef): TitleDetail? = fresh(ref)

    /** The cached detail even if [invalidate]d (within the TTL): shown at once while [get] revalidates. */
    fun lastKnown(ref: TitleRef): TitleDetail? = cache[ref]?.takeIf { now() - it.at < ttlMs }?.detail

    /** The title was played: its page changed. The next [get] refetches; a fetch under way is not reused. */
    fun invalidate(ref: TitleRef) {
        synchronized(lock) {
            versions[ref] = (versions[ref] ?: 0) + 1
            inFlight.remove(ref)
            cache.computeIfPresent(ref) { _, e -> Entry(e.detail, e.at, stale = true) }
        }
        _invalidations.update { it + 1 }
    }

    /** Applies a change the app made itself (e.g. the watchlist toggle) to the cached detail, if any. */
    fun patch(ref: TitleRef, f: (TitleDetail) -> TitleDetail) {
        cache.computeIfPresent(ref) { _, e -> Entry(f(e.detail), e.at, e.stale) }
    }

    /** The app toggled a title's «لیست من» state on the site. */
    fun watchlistChanged() = _watchlistChanges.update { it + 1 }

    /** Session change: forget every title, fetch under way and played backdrop. */
    fun clear() {
        synchronized(lock) {
            epoch++
            versions.clear()
            inFlight.clear()
            cache.clear()
        }
        played.value = emptyMap()
    }

    fun remember(ref: TitleRef, backdropPath: String) = played.update { it + (ref to backdropPath) }

    private fun fresh(ref: TitleRef, maxAgeMs: Long = ttlMs): TitleDetail? =
        cache[ref]?.takeIf { !it.stale && now() - it.at < minOf(maxAgeMs, ttlMs) }?.detail

    companion object {
        /** How old a title page may be when the user opens it (or «ادامه تماشا» decides what is next). */
        const val PAGE_MAX_AGE_MS = 15L * 60 * 1000
    }
}
