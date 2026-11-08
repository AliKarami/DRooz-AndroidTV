package com.alikarami.drooz.data

import android.os.SystemClock
import android.util.Log
import com.alikarami.drooz.BuildConfig
import com.alikarami.drooz.site.model.FeaturedItem
import com.alikarami.drooz.site.model.HomePage
import com.alikarami.drooz.site.model.HomeSection
import com.alikarami.drooz.site.model.PosterRef
import com.alikarami.drooz.site.model.Rating
import com.alikarami.drooz.site.model.TitleCard
import com.alikarami.drooz.site.model.TitleRef
import com.alikarami.drooz.site.writeAtomically
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException

/**
 * The last home page fetched in this process. A home recreated soon after (a warm start: Back out,
 * launch again) shows it at once instead of a skeleton while the page downloads and parses (~2 s on TV).
 * The page is revalidated right after it is shown (see HomeViewModel); past [ttlMs] it is not shown at all.
 * It belongs to one session: AppViewModel clears it when the session is lost and on every login.
 *
 * With a [file], the page also outlives the process: a cold start shows the [stored] copy (at most
 * [STORED_MAX_AGE_MS] old) as stale content while the fresh one downloads. The copy is public card data
 * only (titles, posters, links; [HomePage] holds no session state) and [clear] deletes it, so a logout,
 * a lost session or a login leaves nothing of the previous one on disk.
 */
class HomePageCache(
    private val ttlMs: Long = 10 * 60_000L,
    /** Null keeps the page in memory only. */
    private val file: File? = null,
    /** The stored copy's age is wall time: elapsedRealtime restarts at every boot. */
    private val wallClock: () -> Long = System::currentTimeMillis,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = SystemClock::elapsedRealtime,
) {
    companion object {
        const val STORED_MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000
        private const val TAG = "HomePageCache"
    }

    private var page: HomePage? = null
    private var at = 0L

    /** Guards the file and [generation]; never held across a suspension. */
    private val diskLock = Any()
    /** Bumped by [clear]: a write begun before it must not bring the old session's page back. */
    private var generation = 0
    private val json = Json { ignoreUnknownKeys = true }

    @Synchronized fun fresh(): HomePage? = page?.takeIf { clock() - at < ttlMs }

    /** Forgets the page, in memory and on disk. */
    fun clear() {
        synchronized(this) { page = null }
        synchronized(diskLock) {
            generation++
            file?.let { if (it.exists() && !it.delete() && BuildConfig.DEBUG) Log.w(TAG, "could not delete the stored page") }
        }
    }

    @Synchronized fun put(page: HomePage) {
        this.page = page
        at = clock()
    }

    /** Writes [page] for the next cold start (atomically: a kill leaves the old copy or the new one). Never throws. */
    suspend fun persist(page: HomePage) {
        val f = file ?: return
        val gen = synchronized(diskLock) { generation }
        val bytes = json.encodeToString(StoredHome.serializer(), page.toStored(wallClock())).toByteArray(Charsets.UTF_8)
        withContext(io) {
            synchronized(diskLock) {
                if (gen != generation) return@withContext
                try {
                    writeAtomically(f, bytes)
                } catch (e: IOException) {
                    if (BuildConfig.DEBUG) Log.w(TAG, "persist failed: $e")
                }
            }
        }
    }

    /** The page [persist]ed last, if at most [STORED_MAX_AGE_MS] old; null when none, unreadable or older. */
    suspend fun stored(): HomePage? {
        val f = file ?: return null
        return withContext(io) {
            synchronized(diskLock) {
                val stored = read(f) ?: return@withContext null
                val age = wallClock() - stored.savedAt
                if (stored.v != STORED_VERSION || age !in 0 until STORED_MAX_AGE_MS) {
                    // Too old to show, or from a clock that has since moved back: not kept either.
                    f.delete()
                    return@withContext null
                }
                stored.toModel()
            }
        }
    }

    /** Caller holds [diskLock]. */
    private fun read(f: File): StoredHome? {
        if (!f.isFile) return null
        return try {
            val text = f.readText()
            if (text.isBlank()) null else json.decodeFromString(StoredHome.serializer(), text)
        } catch (e: IOException) {
            if (BuildConfig.DEBUG) Log.w(TAG, "read failed: $e")
            null
        } catch (e: IllegalArgumentException) {
            // SerializationException included; the next persist replaces the file.
            if (BuildConfig.DEBUG) Log.w(TAG, "decode failed: $e")
            null
        }
    }
}

/** Bumped when the stored form changes: an older file is then dropped, not misread. */
private const val STORED_VERSION = 1

// The stored form spells out every field written: nothing but the public page can reach the file.

@Serializable
private data class StoredHome(
    val v: Int,
    val savedAt: Long,
    val featured: List<StoredFeatured>,
    val recentlyStreamed: List<StoredPoster>,
    val sections: List<StoredSection>,
)

@Serializable private data class StoredFeatured(val path: String, val backdropHash: String)
@Serializable private data class StoredPoster(val path: String, val posterHash: String)
@Serializable private data class StoredSection(val title: String, val morePath: String?, val items: List<StoredCard>)
@Serializable private data class StoredRating(val score: Double, val votes: Int)

@Serializable
private data class StoredCard(
    val path: String,
    val title: String,
    val year: Int?,
    val posterHash: String?,
    val durationMin: Int?,
    val imdb: StoredRating?,
    val criticsScore: Int?,
    val genres: List<String>,
    val countries: List<String>,
    val director: String?,
    val stars: List<String>,
    val synopsis: String?,
    val episodeLabel: String?,
    val status: String?,
)

private fun HomePage.toStored(savedAt: Long) = StoredHome(
    v = STORED_VERSION,
    savedAt = savedAt,
    featured = featured.map { StoredFeatured(it.ref.path, it.backdropHash) },
    recentlyStreamed = recentlyStreamed.map { StoredPoster(it.ref.path, it.posterHash) },
    sections = sections.map { s ->
        StoredSection(s.title, s.morePath, s.items.map { c ->
            StoredCard(
                c.ref.path, c.title, c.year, c.posterHash, c.durationMin, c.imdb?.let { StoredRating(it.score, it.votes) },
                c.criticsScore, c.genres, c.countries, c.director, c.stars, c.synopsis, c.episodeLabel, c.status,
            )
        })
    },
)

/** An item whose link no longer parses is dropped, not the page. */
private fun StoredHome.toModel() = HomePage(
    featured = featured.mapNotNull { f -> TitleRef.fromPath(f.path)?.let { FeaturedItem(it, f.backdropHash) } },
    recentlyStreamed = recentlyStreamed.mapNotNull { p -> TitleRef.fromPath(p.path)?.let { PosterRef(it, p.posterHash) } },
    sections = sections.map { s ->
        HomeSection(s.title, s.morePath, s.items.mapNotNull { c ->
            TitleRef.fromPath(c.path)?.let { ref ->
                TitleCard(
                    ref, c.title, c.year, c.posterHash, c.durationMin, c.imdb?.let { Rating(it.score, it.votes) },
                    c.criticsScore, c.genres, c.countries, c.director, c.stars, c.synopsis, c.episodeLabel, c.status,
                )
            }
        })
    },
)
