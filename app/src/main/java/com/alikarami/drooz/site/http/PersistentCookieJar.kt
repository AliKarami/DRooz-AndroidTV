package com.alikarami.drooz.site.http

import com.alikarami.drooz.site.SiteConfig
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import java.io.File

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

    init {
        if (file.exists()) {
            file.readLines().filter { it.isNotBlank() }.forEach { line ->
                runCatching { json.decodeFromString(Stored.serializer(), line) }.getOrNull()?.let { s ->
                    cookies += Cookie.Builder().name(s.name).value(s.value).path(s.path).expiresAt(s.expiresAt)
                        .apply { if (s.hostOnly) hostOnlyDomain(s.domain) else domain(s.domain) }
                        .apply { if (s.secure) secure() }
                        .apply { if (s.httpOnly) httpOnly() }
                        .build()
                }
            }
            dropExpired()
        }
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

    private fun persist() {
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        tmp.writeText(cookies.joinToString("\n") { c ->
            json.encodeToString(Stored.serializer(),
                Stored(c.name, c.value, c.domain, c.path, c.expiresAt, c.secure, c.httpOnly, c.hostOnly))
        })
        tmp.renameTo(file)
    }
}
