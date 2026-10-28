package com.alikarami.drooz

import android.content.Context
import coil3.ImageLoader
import com.alikarami.drooz.data.AuthApi
import com.alikarami.drooz.data.AuthApiAdapter
import com.alikarami.drooz.data.SiteApi
import com.alikarami.drooz.data.SiteApiAdapter
import com.alikarami.drooz.data.TitleInfoRepository
import com.alikarami.drooz.site.SiteConfig
import com.alikarami.drooz.ui.common.buildImageLoader
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
    val siteApi: SiteApi = SiteApiAdapter(repository)
    val authApi: AuthApi = AuthApiAdapter(auth)
    val titleInfo = TitleInfoRepository(siteApi)

    /** For Media3: carries the site cookies (subtitles need them) and the browser UA. */
    val mediaClient: OkHttpClient = baseClient.newBuilder()
        .cookieJar(cookieJar)
        .addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder().header("User-Agent", SiteConfig.USER_AGENT).build())
        }
        .build()

    val imageLoader: ImageLoader = buildImageLoader(appContext, mediaClient)
}
