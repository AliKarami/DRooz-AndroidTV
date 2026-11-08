package com.alikarami.drooz.site.http

import com.alikarami.drooz.site.SiteConfig
import com.alikarami.drooz.site.SiteException
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException

data class SiteResponse(val finalPath: String, val body: String)

/**
 * Thin HTTP layer for the site: cookies, browser UA, and typed failures.
 *
 * The site's server silently drops a share of requests (TLS completes, no response ever comes;
 * healthy responses arrive in < 1 s). GETs therefore use a short [timeoutMs] and are retried up to
 * [maxAttempts] times on timeouts and gateway errors, [retryDelayMs] apart; a 429 is retried once its
 * `Retry-After` has passed (when that is short). [callTimeoutMs] bounds one whole GET, so a body that
 * drips in cannot hold a screen past it. POSTs are never retried.
 */
class SiteHttp(
    private val baseUrl: HttpUrl,
    private val jar: PersistentCookieJar,
    client: OkHttpClient = OkHttpClient(),
    timeoutMs: Long = 8_000,
    private val maxAttempts: Int = 3,
    postTimeoutMs: Long = 30_000,
    callTimeoutMs: Long = 20_000,
    private val retryDelayMs: Long = 500,
) {
    private val client: OkHttpClient = client.newBuilder()
        .cookieJar(jar)
        .followRedirects(true)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
        .callTimeout(callTimeoutMs, TimeUnit.MILLISECONDS)
        .addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder().header("User-Agent", SiteConfig.USER_AGENT).build())
        }
        .build()

    /** Login steps can legitimately take several seconds and must never be re-sent by OkHttp. */
    private val postClient = this.client.newBuilder()
        .readTimeout(postTimeoutMs, TimeUnit.MILLISECONDS)
        .callTimeout(postTimeoutMs + POST_CALL_MARGIN_MS, TimeUnit.MILLISECONDS)
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
                val wait = retryWaitMs(e) ?: throw e
                if (attempt >= attempts) throw e
                delay(wait)
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

    /**
     * Runs [request] asynchronously; cancelling the coroutine cancels the OkHttp call. [handle] runs on
     * the OkHttp callback thread (body reads are blocking) and may throw [SiteException]s; an
     * [IOException] becomes [SiteException.NetworkError].
     */
    private suspend fun <T> call(client: OkHttpClient, request: Request, handle: (Response) -> T): T =
        suspendCancellableCoroutine { cont ->
            val call = client.newCall(request)
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (cont.isActive) cont.resumeWithException(SiteException.NetworkError(e))
                }

                override fun onResponse(call: Call, response: Response) {
                    val result = try {
                        response.use { Result.success(handle(it)) }
                    } catch (e: IOException) {
                        Result.failure(SiteException.NetworkError(e))
                    } catch (e: Throwable) {
                        Result.failure(e)
                    }
                    if (cont.isActive) cont.resumeWith(result)
                }
            })
        }

    private fun resolve(path: String): HttpUrl =
        baseUrl.resolve(path) ?: throw IllegalArgumentException("bad path $path")

    private suspend fun execute(request: Request, requiresAuth: Boolean, client: OkHttpClient = this.client): SiteResponse =
        call(client, request) { r ->
            val body = r.body.string()
            checkStatus(r, body)
            val finalPath = r.request.url.encodedPath
            if (requiresAuth && finalPath.startsWith(SiteConfig.LOGIN_PATH)) throw SiteException.SessionExpired()
            if (body.length < 4000 && body.contains("setCookie(window.devicePixelRatio)")) {
                throw SiteException.ParseError("scr-stub")
            }
            SiteResponse(finalPath, body)
        }

    /** How long to wait before retrying after [e]; null when [e] is not worth a retry. */
    private fun retryWaitMs(e: SiteException): Long? = when {
        e is SiteException.NetworkError -> retryDelayMs
        e is SiteException.HttpError && e.code in GATEWAY_ERRORS -> retryDelayMs
        e is SiteException.HttpError && e.code == TOO_MANY_REQUESTS -> {
            val after = e.retryAfterSeconds?.times(1_000) ?: retryDelayMs
            after.takeIf { it <= MAX_RETRY_AFTER_MS }?.coerceAtLeast(retryDelayMs)
        }
        else -> null
    }

    private companion object {
        val GATEWAY_ERRORS = setOf(502, 503, 504)
        const val TOO_MANY_REQUESTS = 429
        /** A longer `Retry-After` is not waited out: the error is shown instead. */
        const val MAX_RETRY_AFTER_MS = 5_000L
        /** On top of the POST read timeout: connect and TLS. */
        const val POST_CALL_MARGIN_MS = 15_000L
    }

    private fun checkStatus(r: Response, body: String) {
        if (r.isSuccessful) return
        if ((r.code == 403 || r.code == 503) && (body.contains("cf-chl") || body.contains("Just a moment"))) {
            throw SiteException.Blocked()
        }
        throw SiteException.HttpError(r.code, r.header("Retry-After")?.trim()?.toLongOrNull())
    }
}
