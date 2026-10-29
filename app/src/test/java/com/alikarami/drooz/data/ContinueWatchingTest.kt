package com.alikarami.drooz.data

import com.alikarami.drooz.site.model.Episode
import com.alikarami.drooz.site.model.PosterRef
import com.alikarami.drooz.site.model.Season
import com.alikarami.drooz.site.model.StreamCatalog
import com.alikarami.drooz.site.model.TitleCard
import com.alikarami.drooz.site.model.TitleDetail
import com.alikarami.drooz.site.model.TitleKind
import com.alikarami.drooz.site.model.TitleRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ContinueWatchingTest {
    private fun ref(id: Long, kind: TitleKind = TitleKind.SERIES) = TitleRef(kind, id, "t$id")

    private fun entry(id: String, ref: TitleRef, pos: Long, dur: Long, updatedAt: Long) = WatchEntry(
        streamId = id, refPath = ref.path, season = 1, episode = 1,
        positionMs = pos, durationMs = dur, updatedAt = updatedAt, finished = false,
    )

    private fun ep(s: Int, n: Int, id: String = "s${s}e$n", played: Boolean = false) = Episode(
        season = s, number = n, streamId = id,
        videos = emptyList(), audios = emptyList(), subtitles = emptyList(), played = played,
    )

    private fun detail(ref: TitleRef, main: List<Season>, dub: List<Season> = emptyList()): TitleDetail {
        val card = TitleCard(
            ref, "t", null, null, null, null, null, emptyList(), emptyList(), null, emptyList(), null, null, null,
        )
        return TitleDetail(card, null, emptyList(), StreamCatalog(main, dub), emptyList(), null)
    }

    @Test
    fun order_serverFirst_thenLocalOnlyNewestFirst_dedupByPostId() {
        val a = ref(1); val b = ref(2); val c = ref(3); val d = ref(4)
        val local = listOf(
            entry("b", b, 1, 10, 50), entry("c", c, 1, 10, 10), entry("d", d, 1, 10, 30),
        )
        val r = ContinueWatching.order(listOf(PosterRef(a, "ha"), PosterRef(b, "hb")), local)
        assertEquals(listOf(a to "ha", b to "hb", d to null, c to null), r)
    }

    @Test
    fun order_skipsUnparseableRefPath() {
        val c = ref(3)
        val bad = entry("x", c, 1, 10, 99).copy(refPath = "/nope/")
        val r = ContinueWatching.order(emptyList(), listOf(bad, entry("c", c, 1, 10, 1)))
        assertEquals(listOf(c to null), r)
    }

    @Test
    fun resolve_seriesWatchedOnServer_showsNextEpisodeLabel() {
        val r = ref(1)
        val seasons = listOf(Season(1, listOf(ep(1, 1, played = true), ep(1, 2, played = true), ep(1, 3, played = true), ep(1, 4))))
        val item = ContinueWatching.resolve(r, "h", detail(r, seasons), emptyMap(), dub = false)!!
        assertEquals("فصل ۱ · قسمت ۴", item.subtitle)
        assertEquals(ContinuePlay("s1e4"), item.play)
        assertNull(item.progress)
        assertEquals("h", item.posterHash)
    }

    @Test
    fun resolve_seriesResume_hasProgress() {
        val r = ref(1)
        val seasons = listOf(Season(1, listOf(ep(1, 1), ep(1, 2))))
        val entries = mapOf("s1e2" to entry("s1e2", r, 600_000, 2_400_000, 5))
        val item = ContinueWatching.resolve(r, null, detail(r, seasons), entries, dub = false)!!
        assertEquals("فصل ۱ · قسمت ۲", item.subtitle)
        assertEquals(ContinuePlay("s1e2"), item.play)
        assertNotNull(item.progress)
        assertEquals(0.25f, item.progress!!, 0.001f)
    }

    @Test
    fun resolve_fullyWatchedSeries_isDropped() {
        val r = ref(1)
        val seasons = listOf(Season(1, listOf(ep(1, 1, played = true), ep(1, 2, played = true))))
        assertNull(ContinueWatching.resolve(r, null, detail(r, seasons), emptyMap(), dub = false))
    }

    @Test
    fun resolve_filmResume_remainingMinutes() {
        val r = ref(9, TitleKind.FILM)
        val seasons = listOf(Season(1, listOf(ep(1, 1, id = "f"))))
        val entries = mapOf("f" to entry("f", r, 3_000_000, 3_000_000 + 94 * 60_000L, 5))
        val item = ContinueWatching.resolve(r, null, detail(r, seasons), entries, dub = false)!!
        assertEquals("۹۴ دقیقه باقی‌مانده", item.subtitle)
        assertNotNull(item.progress)
        assertEquals(ContinuePlay("f"), item.play)
    }

    @Test
    fun resolve_filmPlayedOnServer_isDropped() {
        val r = ref(9, TitleKind.FILM)
        val seasons = listOf(Season(1, listOf(ep(1, 1, id = "f", played = true))))
        assertNull(ContinueWatching.resolve(r, null, detail(r, seasons), emptyMap(), dub = false))
    }

    @Test
    fun resolve_usesDubCatalogWhenDub() {
        val r = ref(1)
        val main = listOf(Season(1, listOf(ep(1, 1, id = "m1"), ep(1, 2, id = "m2"))))
        val dub = listOf(Season(1, listOf(ep(1, 1, id = "d1"), ep(1, 2, id = "d2"))))
        val entries = mapOf("d1" to entry("d1", r, 600_000, 2_400_000, 5))
        val item = ContinueWatching.resolve(r, null, detail(r, main, dub), entries, dub = true)!!
        assertEquals(ContinuePlay("d1"), item.play)
        assertNotNull(item.progress)
    }
}
