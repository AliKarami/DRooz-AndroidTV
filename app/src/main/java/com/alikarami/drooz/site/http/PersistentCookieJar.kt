package com.alikarami.drooz.site.http

import android.util.Log
import com.alikarami.drooz.BuildConfig
import com.alikarami.drooz.site.SiteConfig
import com.alikarami.drooz.site.writeAtomically
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import java.io.File
import java.io.IOException

/**
 * Cookie jar persisted to [file] (one JSON object per line) so the site session survives restarts.
 * Adds the `scr=1` cookie to every request for [siteHost]; `scr` itself is never stored.
 * The synced [write] runs outside the jar's monitor (every request needs it) and only when the content changed.
 */
class PersistentCookieJar(
    private val file: File,
    private val siteHost: String = SiteConfig.HOST,
    private val now: () -> Long = System::currentTimeMillis,
    private val write: (File, ByteArray) -> Unit = ::writeAtomically,
) : CookieJar {

    @Serializable
    private data class Stored(
        val name: String, val value: String, val domain: String, val path: String,
        val expiresAt: Long, val secure: Boolean, val httpOnly: Boolean, val hostOnly: Boolean,
    )

    private val json = Json { ignoreUnknownKeys = true }
    private val cookies = mutableListOf<Cookie>()
    /** Bumped per snapshot under the jar's monitor: a writer never replaces a newer snapshot with an older one. */
    private var snapshots = 0L
    private val writeLock = Any()
    private var written = 0L
    private var lastText: String? = null

    /**
     * Built in Application.onCreate, so nothing here may throw: an unreadable file starts an empty jar and
     * a line that does not decode to a valid cookie is skipped (the others, `sf` included, are kept).
     */
    init {
        val lines = try {
            if (file.isFile) file.readLines() else emptyList()
        } catch (e: IOException) {
            if (BuildConfig.DEBUG) Log.w(TAG, "cookie file unreadable: $e")
            emptyList()
        }
        lines.filter { it.isNotBlank() }.forEach { line ->
            runCatching {
                val s = json.decodeFromString(Stored.serializer(), line)
                Cookie.Builder().name(s.name).value(s.value).path(s.path).expiresAt(s.expiresAt)
                    .apply { if (s.hostOnly) hostOnlyDomain(s.domain) else domain(s.domain) }
                    .apply { if (s.secure) secure() }
                    .apply { if (s.httpOnly) httpOnly() }
                    .build()
            }.getOrNull()?.let { cookies += it }
        }
        dropExpired()
    }

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val snapshot = synchronized(this) {
            for (cookie in cookies) {
                if (cookie.name == SiteConfig.SCR_COOKIE) continue
                this.cookies.removeAll { it.name == cookie.name && it.domain == cookie.domain && it.path == cookie.path }
                this.cookies += cookie
            }
            dropExpired()
            snapshot()
        }
        persist(snapshot)
    }

    @Synchronized
    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        dropExpired()
        val matching = cookies.filter { it.matches(url) }
        if (url.host != siteHost) return matching
        val scr = Cookie.Builder().name(SiteConfig.SCR_COOKIE).value(SiteConfig.SCR_VALUE)
            .hostOnlyDomain(url.host).path("/").build()
        return matching + scr
    }

    @Synchronized
    fun cookie(name: String): String? {
        dropExpired()
        return cookies.lastOrNull { it.name == name }?.value
    }

    /** Removes all cookies except those named in [keep] (logout keeps the device id `sf`). */
    fun clear(keep: Set<String> = emptySet()) {
        val snapshot = synchronized(this) {
            cookies.retainAll { it.name in keep }
            snapshot()
        }
        persist(snapshot)
    }

    private fun dropExpired() {
        val t = now()
        cookies.removeAll { it.expiresAt <= t }
    }

    /** Under the jar's monitor. */
    private fun snapshot(): Pair<Long, String> = ++snapshots to cookies.joinToString("\n") { c ->
        json.encodeToString(Stored.serializer(),
            Stored(c.name, c.value, c.domain, c.path, c.expiresAt, c.secure, c.httpOnly, c.hostOnly))
    }

    /**
     * Atomic; skipped when a newer snapshot was written already or the content is unchanged. A disk error
     * keeps the cookies in memory and must not fail the HTTP call that set them.
     */
    private fun persist(snapshot: Pair<Long, String>) {
        val (n, text) = snapshot
        synchronized(writeLock) {
            if (n <= written) return
            written = n
            if (text == lastText) return
            try {
                write(file, text.toByteArray(Charsets.UTF_8))
                lastText = text
            } catch (e: IOException) {
                if (BuildConfig.DEBUG) Log.w(TAG, "cookie persist failed: $e")
            }
        }
    }

    private companion object {
        const val TAG = "CookieJar"
    }
}
