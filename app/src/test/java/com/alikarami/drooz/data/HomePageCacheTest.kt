package com.alikarami.drooz.data

import com.alikarami.drooz.site.model.FeaturedItem
import com.alikarami.drooz.site.model.HomePage
import com.alikarami.drooz.site.model.HomeSection
import com.alikarami.drooz.site.model.PosterRef
import com.alikarami.drooz.site.model.Rating
import com.alikarami.drooz.site.model.TitleCard
import com.alikarami.drooz.site.model.TitleKind
import com.alikarami.drooz.site.model.TitleRef
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class HomePageCacheTest {
    @get:Rule val tmp = TemporaryFolder()

    private var now = 1_000L
    private var wall = 1_700_000_000_000L
    private val cache = HomePageCache(ttlMs = 600_000, clock = { now })
    private val page = HomePage(emptyList(), emptyList(), emptyList())

    @Test fun emptyUntilPut() = assertNull(cache.fresh())

    @Test fun freshWithinTtl() {
        cache.put(page)
        now += 599_999
        assertEquals(page, cache.fresh())
    }

    @Test fun expiresAfterTtl() {
        cache.put(page)
        now += 600_000
        assertNull(cache.fresh())
    }

    // ── On disk (cold start) ──────────────────────────────────────────────

    private val file get() = File(tmp.root, "home_page.json")
    private fun TestScope.disk() =
        HomePageCache(ttlMs = 600_000, clock = { now }, file = file, wallClock = { wall }, io = StandardTestDispatcher(testScheduler))

    private val series = TitleRef(TitleKind.SERIES, 42, "the-pitt")
    private val film = TitleRef(TitleKind.FILM, 7, "heat")
    private val full = HomePage(
        featured = listOf(FeaturedItem(film, "bd7")),
        recentlyStreamed = listOf(PosterRef(series, "p42")),
        sections = listOf(
            HomeSection("فیلم های برگزیده", "/list/film/", listOf(
                TitleCard(film, "Heat", 1995, "p7", 170, Rating(8.3, 181000), 86, listOf("Crime", "Drama"),
                    listOf("USA"), "Michael Mann", listOf("Al Pacino", "Robert De Niro"), "A heist.", null, null),
                TitleCard(series, "The Pitt", null, null, null, null, null, emptyList(), emptyList(), null,
                    emptyList(), null, "قسمت ۰۷ فصل اول", "در حال پخش"),
            )),
            HomeSection("سریال های بروز شده", null, emptyList()),
        ),
    )

    @Test fun persistedPageSurvivesAProcessRestart() = runTest {
        disk().persist(full)
        assertEquals(full, disk().stored())
    }

    @Test fun storedPageUpToSevenDaysOld() = runTest {
        disk().persist(full)
        wall += HomePageCache.STORED_MAX_AGE_MS - 1
        assertEquals(full, disk().stored())
        wall += 1
        assertNull(disk().stored())
        assertFalse(file.exists())                      // an expired page is not kept either
    }

    // A clock set back (a TV without network time) cannot make an old page look new.
    @Test fun pageFromTheFutureIsNotShown() = runTest {
        disk().persist(full)
        wall -= 60_000
        assertNull(disk().stored())
    }

    @Test fun clearForgetsThePersistedPage() = runTest {
        val c = disk()
        c.persist(full)
        c.clear()
        assertNull(c.stored())
        assertNull(disk().stored())
        assertFalse(file.exists())
    }

    // clear() runs on main (session lost, login): the delete goes to io, never waiting there on a write's fsync.
    @Test fun clearDeletesOnIo() = runTest {
        val c = disk()
        c.persist(full)
        c.put(full)
        c.clear()
        assertNull(c.fresh())                           // memory at once
        assertTrue(file.exists())                       // the file on io, after
        testScheduler.advanceUntilIdle()
        assertFalse(file.exists())
    }

    @Test fun missingOrCorruptFileIsNoPage() = runTest {
        assertNull(disk().stored())
        file.writeText("{not json")
        assertNull(disk().stored())
        file.writeText("")
        assertNull(disk().stored())
    }

    @Test fun persistWithoutFileIsANoOp() = runTest {
        cache.persist(full)
        assertNull(cache.stored())
    }

    // Only public card data is written: the file has exactly these top-level fields (no session state).
    @Test fun fileHoldsOnlyThePublicPage() = runTest {
        disk().persist(full)
        val keys = Json.parseToJsonElement(file.readText()).jsonObject.keys
        assertEquals(setOf("v", "savedAt", "featured", "recentlyStreamed", "sections"), keys)
        assertFalse(File(tmp.root, "home_page.json.tmp").exists())
    }
}
