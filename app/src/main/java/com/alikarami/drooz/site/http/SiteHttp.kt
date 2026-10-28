package com.alikarami.drooz.site.http

import com.alikarami.drooz.site.SiteConfig
import com.alikarami.drooz.site.SiteException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

data class SiteResponse(val finalPath: String, val body: String)

/**
 * Thin HTTP layer for the site: cookies, browser UA, and typed failures.
 *
 * The site's server silently drops a share of requests (TLS completes, no response ever comes;
 * healthy responses arrive in < 1 s). GETs therefore use a short [timeoutMs] and are retried up to
 * [maxAttempts] times on timeouts and gateway errors. POSTs are never retried.
 */
class SiteHttp(
    private val baseUrl: HttpUrl,
    private val jar: PersistentCookieJar,
    client: OkHttpClient = OkHttpClient(),
    timeoutMs: Long = 8_000,
    private val maxAttempts: Int = 3,
    postTimeoutMs: Long = 30_000,
) {
    private val client: OkHttpClient = client.newBuilder()
        .cookieJar(jar)
        .followRedirects(true)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
        .addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder().header("User-Agent", SiteConfig.USER_AGENT).build())
        }
        .build()

    private val noRedirectClient = this.client.newBuilder().followRedirects(false).build()

    /** Login steps can legitimately take several seconds and must never be re-sent by OkHttp. */
    private val postClient = this.client.newBuilder()
        .readTimeout(postTimeoutMs, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(false)
        .build()

    /** Pass [retry] = false for GETs that change server state (toggles): a dropped-but-processed request must not repeat. */
    suspend fun get(path: String, requiresAuth: Boolean = true, retry: Boolean = true): SiteResponse {
        val attempts = if (retry) maxAttempts else 1
        val request = Request.Builder().url(resolve(path)).build()
        var attempt = 1
        while (true) {
            try {
                return execute(request, requiresAuth)
            } catch (e: SiteException) {
                val transient = e is SiteException.NetworkError ||
                    (e is SiteException.HttpError && e.code in GATEWAY_ERRORS)
                if (!transient || attempt >= attempts) throw e
                attempt++
            }
        }
    }

    suspend fun postMultipart(path: String, fields: List<Pair<String, String>>): SiteResponse {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .apply { fields.forEach { (k, v) -> addFormDataPart(k, v) } }
            .build()
        return execute(Request.Builder().url(resolve(path)).post(body).build(), requiresAuth = false, client = postClient)
    }

    /** Returns the redirect target of [path] without downloading it (used for `/dln/` file links). */
    suspend fun getFinalUrl(path: String): String = withContext(Dispatchers.IO) {
        try {
            noRedirectClient.newCall(Request.Builder().url(resolve(path)).build()).execute().use { r ->
                if (r.isRedirect) {
                    val location = r.header("Location") ?: throw SiteException.ParseError("redirect location")
                    r.request.url.resolve(location)?.toString() ?: location
                } else {
                    checkStatus(r, r.body.string())
                    r.request.url.toString()
                }
            }
        } catch (e: IOException) {
            throw SiteException.NetworkError(e)
        }
    }

    private fun resolve(path: String): HttpUrl =
        baseUrl.resolve(path) ?: throw IllegalArgumentException("bad path $path")

    private suspend fun execute(request: Request, requiresAuth: Boolean, client: OkHttpClient = this.client): SiteResponse =
        withContext(Dispatchers.IO) {
        try {
            client.newCall(request).execute().use { r ->
                val body = r.body.string()
                checkStatus(r, body)
                val finalPath = r.request.url.encodedPath
                if (requiresAuth && finalPath.startsWith(SiteConfig.LOGIN_PATH)) throw SiteException.SessionExpired()
                if (body.length < 4000 && body.contains("setCookie(window.devicePixelRatio)")) {
                    throw SiteException.ParseError("scr-stub")
                }
                SiteResponse(finalPath, body)
            }
        } catch (e: IOException) {
            throw SiteException.NetworkError(e)
        }
    }

    private companion object {
        val GATEWAY_ERRORS = setOf(502, 503, 504)
    }

    private fun checkStatus(r: Response, body: String) {
        if (r.isSuccessful) return
        if ((r.code == 403 || r.code == 503) && (body.contains("cf-chl") || body.contains("Just a moment"))) {
            throw SiteException.Blocked()
        }
        throw SiteException.HttpError(r.code)
    }
}
