package com.alikarami.drooz

import android.content.Context
import com.alikarami.drooz.site.SiteConfig
import com.alikarami.drooz.site.SiteRepository
import com.alikarami.drooz.site.auth.AuthService
import com.alikarami.drooz.site.auth.CredentialStore
import com.alikarami.drooz.site.auth.KeystoreSecretBox
import com.alikarami.drooz.site.http.PersistentCookieJar
import com.alikarami.drooz.site.http.SiteHttp
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient

/** Manual dependency container. Session files live in no-backup storage so they never leave the device. */
class AppGraph(context: Context) {
    val appContext: Context = context.applicationContext
    private val dir = appContext.noBackupFilesDir

    val cookieJar = PersistentCookieJar(dir.resolve("cookies.jsonl"))
    private val baseClient: OkHttpClient = OkHttpClient.Builder()
        .apply {
            if (BuildConfig.DEBUG) addNetworkInterceptor { chain ->
                val req = chain.request()
                val res = runCatching { chain.proceed(req) }
                    .onFailure { android.util.Log.w("DroozHttp", "${req.method} ${req.url.encodedPath} failed: $it") }
                    .getOrThrow()
                android.util.Log.d("DroozHttp", "${req.method} ${req.url.encodedPath} -> ${res.code} ${res.protocol} " +
                    "server=${res.header("server")} cf-ray=${res.header("cf-ray")} loc=${res.header("location")}")
                res
            }
        }
        .build()

    val http = SiteHttp(SiteConfig.BASE_URL.toHttpUrl(), cookieJar, baseClient)
    val auth = AuthService(http, cookieJar, CredentialStore(dir.resolve("creds.bin"), KeystoreSecretBox()))
    val repository = SiteRepository(http, auth)

    /** For Media3: carries the site cookies (subtitles need them) and the browser UA. */
    val mediaClient: OkHttpClient = baseClient.newBuilder()
        .cookieJar(cookieJar)
        .addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder().header("User-Agent", SiteConfig.USER_AGENT).build())
        }
        .build()
}
