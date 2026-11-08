package com.alikarami.drooz.data

import com.alikarami.drooz.site.SiteException
import com.alikarami.drooz.site.model.CategorizedListPage
import com.alikarami.drooz.site.model.CategoryKind
import com.alikarami.drooz.site.model.Episode
import com.alikarami.drooz.site.model.ListPage
import com.alikarami.drooz.site.model.SiteCategory
import com.alikarami.drooz.site.model.TitleRef
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SessionGuardTest {
    private class Failing(val e: Exception) : SiteApi {
    override suspend fun subtitle(url: String): String = error("unused")
    override suspend fun reportStreamError(streamId: String, event: String, msg: String) {}
        override suspend fun home() = throw e
        override suspend fun list(path: String, page: Int) = throw e
        override suspend fun search(query: String) = throw e
        override suspend fun title(ref: TitleRef) = throw e
        override suspend fun openStream(episode: Episode, ref: TitleRef, lang: String, height: Int) = throw e
        override suspend fun setWatchlist(postId: Long, state: String) = throw e
    }

    @Test fun reportsNotLoggedInAndRethrows() = runTest(UnconfinedTestDispatcher()) {
        val events = SessionEvents()
        var lost = 0
        backgroundScope.launch { events.lost.collect { lost++ } }
        val api = SessionGuardedSiteApi(Failing(SiteException.NotLoggedIn()), events)
        val thrown = runCatching { api.home() }.exceptionOrNull()
        assertTrue(thrown is SiteException.NotLoggedIn)
        assertEquals(1, lost)

        val other = SessionGuardedSiteApi(Failing(SiteException.Blocked()), events)
        assertTrue(runCatching { other.search("x") }.exceptionOrNull() is SiteException.Blocked)
        assertEquals(1, lost)
    }

    @Test fun passesCategoriesThroughAndReportsTheirLostSession() = runTest(UnconfinedTestDispatcher()) {
        val events = SessionEvents()
        var lost = 0
        backgroundScope.launch { events.lost.collect { lost++ } }
        val cats = listOf(SiteCategory("مینی سریال", "/archive/category/tv-mini-series/", CategoryKind.SERIES))
        val ok = object : SiteApi by Failing(SiteException.Blocked()) {
            override suspend fun listWithCategories(path: String) = CategorizedListPage(ListPage(emptyList(), 1, 1), cats)
        }
        assertEquals(cats, SessionGuardedSiteApi(ok, events).listWithCategories("/archive/series/").categories)

        val gone = object : SiteApi by Failing(SiteException.Blocked()) {
            override suspend fun listWithCategories(path: String): CategorizedListPage = throw SiteException.NotLoggedIn()
        }
        assertTrue(runCatching { SessionGuardedSiteApi(gone, events).listWithCategories("/x/") }.exceptionOrNull()
            is SiteException.NotLoggedIn)
        assertEquals(1, lost)
    }
}
