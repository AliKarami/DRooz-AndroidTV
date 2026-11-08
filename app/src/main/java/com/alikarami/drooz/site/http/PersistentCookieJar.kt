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
 */
class PersistentCookieJar(
    private val file: File,
    private val siteHost: String = SiteConfig.HOST,
    private val now: () -> Long = System::currentTimeMillis,
) : CookieJar {

    @Serializable
    private data class Stored(
        val name: String, val value: String, val domain: String, val path: String,
        val expiresAt: Long, val secure: Boolean, val httpOnly: Boolean, val hostOnly: Boolean,
    )

    private val json = Json { ignoreUnknownKeys = true }
    private val cookies = mutableListOf<Cookie>()

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

    @Synchronized
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        for (cookie in cookies) {
            if (cookie.name == SiteConfig.SCR_COOKIE) continue
            this.cookies.removeAll { it.name == cookie.name && it.domain == cookie.domain && it.path == cookie.path }
            this.cookies += cookie
        }
        dropExpired()
        persist()
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
    @Synchronized
    fun clear(keep: Set<String> = emptySet()) {
        cookies.retainAll { it.name in keep }
        persist()
    }

    private fun dropExpired() {
        val t = now()
        cookies.removeAll { it.expiresAt <= t }
    }

    /** Atomic; a disk error keeps the cookies in memory and must not fail the HTTP call that set them. */
    private fun persist() {
        val text = cookies.joinToString("\n") { c ->
            json.encodeToString(Stored.serializer(),
                Stored(c.name, c.value, c.domain, c.path, c.expiresAt, c.secure, c.httpOnly, c.hostOnly))
        }
        try {
            writeAtomically(file, text.toByteArray(Charsets.UTF_8))
        } catch (e: IOException) {
            if (BuildConfig.DEBUG) Log.w(TAG, "cookie persist failed: $e")
        }
    }

    private companion object {
        const val TAG = "CookieJar"
    }
}
