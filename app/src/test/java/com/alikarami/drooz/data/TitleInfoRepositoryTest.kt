package com.alikarami.drooz.data

import com.alikarami.drooz.site.model.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TitleInfoRepositoryTest {
    private fun ref(id: Long) = TitleRef(TitleKind.FILM, id, "s$id")
    private fun detail(r: TitleRef, title: String = "t") = TitleDetail(
        card = TitleCard(r, title, null, null, null, null, null, emptyList(), emptyList(), null, emptyList(), null, null, null),
        channel = null, awards = emptyList(), catalog = StreamCatalog(emptyList(), emptyList()),
        suggestions = emptyList(), trailerThumbHash = null,
    )

    private class FakeApi(val onTitle: suspend (TitleRef) -> TitleDetail) : SiteApi {
        var calls = 0
        override suspend fun title(ref: TitleRef): TitleDetail { calls++; return onTitle(ref) }
        override suspend fun home() = error("unused")
        override suspend fun list(path: String, page: Int) = error("unused")
        override suspend fun search(query: String) = error("unused")
        override suspend fun openStream(episode: Episode, ref: TitleRef, lang: String, height: Int) = error("unused")
        override suspend fun setWatchlist(postId: Long, state: String) = error("unused")
    }

    @Test fun cachesWithinTtl() = runTest {
        val api = FakeApi { detail(it) }
        var t = 0L
        val repo = TitleInfoRepository(api, { t }, 1000, backgroundScope)
        repo.get(ref(1)); t = 999; repo.get(ref(1))
        assertEquals(1, api.calls)
        assertNotNull(repo.peek(ref(1)))
    }

    @Test fun refetchesAfterTtl() = runTest {
        val api = FakeApi { detail(it) }
        var t = 0L
        val repo = TitleInfoRepository(api, { t }, 1000, backgroundScope)
        repo.get(ref(1)); t = 1000
        assertNull(repo.peek(ref(1)))
        repo.get(ref(1))
        assertEquals(2, api.calls)
    }

    @Test fun dedupesConcurrentRequests() = runTest {
        val gate = CompletableDeferred<Unit>()
        val api = FakeApi { gate.await(); detail(it) }
        val repo = TitleInfoRepository(api, { 0 }, 1000, backgroundScope)
        val a = async { repo.get(ref(1)) }
        val b = async { repo.get(ref(1)) }
        runCurrent()
        gate.complete(Unit)
        awaitAll(a, b)
        assertEquals(1, api.calls)
    }

    @Test fun limitsToTwoConcurrentFetches() = runTest {
        val gate = CompletableDeferred<Unit>()
        var inFlight = 0; var max = 0
        val api = FakeApi { inFlight++; max = maxOf(max, inFlight); gate.await(); inFlight--; detail(it) }
        val repo = TitleInfoRepository(api, { 0 }, 1000, backgroundScope)
        val jobs = (1L..5L).map { async { repo.get(ref(it)) } }
        runCurrent()
        assertEquals(2, max)
        gate.complete(Unit)
        jobs.awaitAll()
        assertEquals(2, max)
        assertEquals(5, api.calls)
    }

    @Test fun failureIsNotCached() = runTest {
        var n = 0
        val api = FakeApi { if (n++ == 0) throw RuntimeException("boom") else detail(it) }
        val repo = TitleInfoRepository(api, { 0 }, 1000, backgroundScope)
        assertTrue(runCatching { repo.get(ref(1)) }.isFailure)
        repo.get(ref(1))
        assertEquals(2, api.calls)
    }

    @Test fun rememberStoresPlayedBackdrop() = runTest {
        val repo = TitleInfoRepository(FakeApi { detail(it) }, { 0 }, 1000, backgroundScope)
        repo.remember(ref(1), "/img/1920-1080/h.jpg.webp")
        assertEquals("/img/1920-1080/h.jpg.webp", repo.playedBackdrops.value[ref(1)])
    }

    @Test fun peekIsSafeDuringConcurrentWrites() {
        val api = FakeApi { detail(it) }
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
        val repo = TitleInfoRepository(api, { 0 }, 1000, scope)
        val errors = java.util.concurrent.ConcurrentLinkedQueue<Throwable>()
        val readers = (1..4).map {
            Thread {
                try { repeat(200_000) { i -> repo.peek(ref((i % 2000).toLong())) } } catch (t: Throwable) { errors.add(t) }
            }.also { it.start() }
        }
        kotlinx.coroutines.runBlocking {
            (0L until 2000L).map { scope.async { repo.get(ref(it)) } }.awaitAll()
        }
        readers.forEach { it.join() }
        scope.coroutineContext[kotlinx.coroutines.Job]!!.cancel()
        assertTrue(errors.toString(), errors.isEmpty())
        assertNotNull(repo.peek(ref(1999)))
    }

    // After a play the page changes (played ✓, next episode): the next get() must refetch, while the
    // old detail stays available for an instant display.
    @Test fun invalidateForcesRefetchButKeepsLastKnown() = runTest {
        var n = 0
        val api = FakeApi { detail(it, "v${++n}") }
        val repo = TitleInfoRepository(api, { 0 }, 1000, backgroundScope)
        repo.get(ref(1))
        repo.invalidate(ref(1))
        assertNull(repo.peek(ref(1)))
        assertEquals("v1", repo.lastKnown(ref(1))?.card?.title)
        assertEquals(1, repo.invalidations.value)
        assertEquals("v2", repo.get(ref(1)).card.title)
        assertEquals(2, api.calls)
        assertEquals("v2", repo.peek(ref(1))?.card?.title)
    }

    // A fetch that started before the play may carry the pre-play page: it is neither shared nor cached.
    @Test fun invalidateDropsInFlightFetch() = runTest {
        val gate = CompletableDeferred<Unit>()
        var n = 0
        val api = FakeApi { val v = ++n; gate.await(); detail(it, "v$v") }
        val repo = TitleInfoRepository(api, { 0 }, 1000, backgroundScope)
        val a = async { repo.get(ref(1)) }
        runCurrent()
        repo.invalidate(ref(1))
        val b = async { repo.get(ref(1)) }
        runCurrent()
        gate.complete(Unit)
        assertEquals("v1", a.await().card.title)
        assertEquals("v2", b.await().card.title)
        assertEquals(2, api.calls)
        assertEquals("v2", repo.peek(ref(1))?.card?.title)
    }

    // A session change (logout / other account) must not leak titles, played marks or backdrops.
    @Test fun clearForgetsEverything() = runTest {
        val gate = CompletableDeferred<Unit>()
        val api = FakeApi { if (it == ref(2)) gate.await(); detail(it) }
        val repo = TitleInfoRepository(api, { 0 }, 1000, backgroundScope)
        repo.get(ref(1))
        repo.remember(ref(1), "/img/1920-1080/h.jpg.webp")
        val late = async { repo.get(ref(2)) }
        runCurrent()
        repo.clear()
        assertNull(repo.peek(ref(1)))
        assertNull(repo.lastKnown(ref(1)))
        assertTrue(repo.playedBackdrops.value.isEmpty())
        gate.complete(Unit)
        late.await()
        assertNull(repo.lastKnown(ref(2)))
        repo.get(ref(1))
        assertEquals(3, api.calls)
    }

    @Test fun patchUpdatesCachedDetail() = runTest {
        val repo = TitleInfoRepository(FakeApi { detail(it) }, { 0 }, 1000, backgroundScope)
        repo.get(ref(1))
        repo.patch(ref(1)) { it.copy(inWatchlist = true) }
        assertEquals(true, repo.peek(ref(1))?.inWatchlist)
        repo.patch(ref(2)) { it.copy(inWatchlist = true) } // nothing cached: no-op
        assertNull(repo.lastKnown(ref(2)))
    }
}
