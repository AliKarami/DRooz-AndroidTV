# Plan 1 — Foundation & Site Client Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A buildable Android TV app that logs into the site, parses every page type the app needs (verified against saved real pages), and plays a title end-to-end on the Google TV Streamer through a bare-bones debug UI.

**Architecture:** Single `app` module, package `com.alikarami.drooz`. A `site` layer (OkHttp + persistent cookies + Jsoup/kotlinx-serialization parsers + `SiteRepository`) has no Android UI dependencies and is unit-tested on the JVM against sanitized HTML fixtures. A throwaway `ui/debug` layer (Compose for TV + Media3) proves the pipeline on device; Plans 2–4 replace it.

**Tech Stack:** AGP 9.5.0, Gradle 9.8.2, Kotlin 2.4.21, KSP 2.3.12, compileSdk 37 / minSdk 31 / targetSdk 37, Compose BOM 2026.09.00, androidx.tv:tv-material 1.1.0, Media3 1.11.1, OkHttp 5.5.1 (+ mockwebserver 5.5.1), Jsoup 1.23.2, kotlinx-serialization-json 1.12.0, coroutines 1.11.0, activity-compose 1.13.0, lifecycle 2.11.0, navigation-compose 2.10.2, datastore 1.3.0, core-ktx 1.19.1, JUnit 4.13.2, Turbine 1.2.1.

**Spec:** `docs/superpowers/specs/2026-10-28-dlrooz-tv-design.md` (read §4 "Site contract" before Tasks 3–9).

**Roadmap (later plans, written after this one ships):** Plan 2 Browse UI (design system, Netflix-style home, title page, lists, search) · Plan 3 Player (spec §6 table) · Plan 4 Watch state, Continue Watching, settings, polish.

## Global Constraints

- Base URL `https://robin.robert-redford.net`; every site request carries cookie `scr=1` and User-Agent `Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/154.0.0.0 Safari/537.36`.
- Cookies (`sf`, `cf`, …) persist forever across launches; never cleared except by explicit logout. Re-login only on a detected expired session, keeping `sf`.
- Never request a `/stream/...` page except immediately before the user plays something (it marks the title as streamed on the account). Never cache `master.m3u8` URLs (valid ~1 min).
- **The GitHub repo is public.** Never commit credentials, cookies, the real user token, user id, username, phone number or m3u8 keys. Fixtures are committed only after sanitization (Task 2), enforced by `FixtureHygieneTest`.
- Credentials for local scripts live in `~/.drtv-creds` (line 1 username, line 2 password) and `secrets.properties` (git-ignored). Mobile number is entered at runtime, never in the repo.
- All user-facing strings Persian; English titles kept as-is.

## Review Focus

1. **Session expired mid-use** (cookie revoked server-side) → the next site call transparently re-logs in with stored credentials, keeping `sf`, and retries once; a second failure surfaces `NotLoggedIn` instead of looping. *Test: `SiteRepositoryTest.relogsInOnceOnExpiredSession` and `.givesUpAfterSecondExpiry` (Task 8).*
2. **Login page asks for something unexpected** (new field, captcha, changed wording) → result `Failed` carrying the page's alert text, never a crash or a fake success. *Test: `LoginParserTest.unknownPageYieldsFailedWithAlertText` (Task 4).*
3. **Title without dub / film vs series / series where some episodes are missing `played`** → catalog shapes parse without exceptions (films are a JSON array, series a JSON object keyed by season numbers as strings). *Tests in `TitleParserTest` (Task 6) cover film-array and series-object forms.*
4. **Network failure / non-200 / Cloudflare HTML instead of the page** → typed `SiteException` subclasses, not Jsoup NPEs. *Test: `SiteHttpTest.non200ThrowsHttpError` and `.cloudflareChallengeThrowsBlocked` (Task 3).*
5. **Persian digits and number words in metadata** («۷.۹ از ۱۰ با ۱۸۱ هزار رای», «۱۴۱ دقیقه») → parsed to numbers correctly. *Test: `PersianTextTest` (Task 5).*

---

## File Structure

```
settings.gradle.kts, build.gradle.kts, gradle.properties, gradle/libs.versions.toml, gradle/wrapper/*
app/build.gradle.kts
app/src/main/AndroidManifest.xml                     leanback launcher, INTERNET, banner
app/src/main/res/drawable/banner.xml, values/strings.xml, font/vazirmatn_*.ttf
app/src/main/java/com/alikarami/drooz/
  DroozApp.kt                                        Application; builds AppGraph
  AppGraph.kt                                        manual DI container
  site/SiteConfig.kt                                 BASE_URL, USER_AGENT, constants
  site/SiteException.kt                              sealed exception types
  site/http/PersistentCookieJar.kt                   file-backed cookie jar + scr=1
  site/http/SiteHttp.kt                              GET/POST helpers, expiry/blocked detection
  site/auth/LoginParser.kt                           login HTML → LoginPage
  site/auth/AuthService.kt                           2-step login, relogin, device report
  site/auth/CredentialStore.kt                       encrypted creds (SecretBox)
  site/auth/KeystoreSecretBox.kt                     AES-GCM via AndroidKeyStore
  site/model/Models.kt                               all data classes below
  site/parse/PersianText.kt                          digits, numbers, durations
  site/parse/CardParser.kt                           shared "title card" metadata parsing
  site/parse/HomeParser.kt, ListParser.kt, SearchParser.kt, TitleParser.kt, StreamPageParser.kt
  site/ImageUrls.kt                                  /img/W-H/ URL builder
  site/SiteRepository.kt                             the API the UI uses
  ui/debug/MainActivity.kt, DebugScreens.kt, DebugPlayerActivity.kt
app/src/test/java/com/alikarami/drooz/...            JVM tests mirroring the above
app/src/test/resources/fixtures/*.html               sanitized real pages
scripts/capture_fixtures.sh, scripts/sanitize_fixture.py
```

### Models (Task 5 creates; everything later consumes)

```kotlin
enum class TitleKind { FILM, SERIES }
data class TitleRef(val kind: TitleKind, val postId: Long, val slug: String) { val path: String /* "/post/film/5744530/slug/" */ }
data class Rating(val score: Double, val votes: Int)                 // votes absolute (181 هزار → 181000)
data class TitleCard(
  val ref: TitleRef, val title: String, val year: Int?, val posterHash: String?,
  val durationMin: Int?, val imdb: Rating?, val criticsScore: Int?, val genres: List<String>,
  val countries: List<String>, val director: String?, val stars: List<String>, val synopsis: String?,
  val episodeLabel: String?, val status: String?)                     // series badge: «قسمت ۰۷ فصل سوم», «در حال پخش»
data class FeaturedItem(val ref: TitleRef, val backdropHash: String)
data class HomeSection(val title: String, val morePath: String?, val items: List<TitleCard>)
data class PosterRef(val ref: TitleRef, val posterHash: String)          // home "recently streamed" items carry only link + poster
data class HomePage(val featured: List<FeaturedItem>, val recentlyStreamed: List<PosterRef>, val sections: List<HomeSection>)
data class ListPage(val items: List<TitleCard>, val page: Int, val lastPage: Int)
data class VideoVariant(val id: String, val width: Int, val height: Int, val size: String)
data class AudioTrack(val id: String, val lang: String, val channels: Int)   // lang "original" | "dubbed-fa"
data class SubtitleRef(val id: String, val lang: String)                     // "fa" | "en"
data class Episode(val season: Int, val number: Int, val streamId: String, val videos: List<VideoVariant>,
                   val audios: List<AudioTrack>, val subtitles: List<SubtitleRef>, val played: Boolean)
data class Season(val number: Int, val episodes: List<Episode>)              // sorted by number
data class StreamCatalog(val main: List<Season>, val dub: List<Season>)      // dub empty when absent; films: one Season(1) with one Episode(1)
data class TitleDetail(val card: TitleCard, val channel: String?, val awards: List<String>,
                       val catalog: StreamCatalog, val suggestions: List<TitleRef>, val trailerThumbHash: String?)
data class SubtitleTrack(val label: String, val url: String)                 // labels as on site: Farsi, English, English + Farsi, Farsi for Dub
data class StreamSession(val title: String, val masterUrl: String, val backdropPath: String?,
                         val subtitles: List<SubtitleTrack>, val qualityLabels: Map<Int, String>, // key = kbps
                         val gains: List<Pair<Double, Double>>, val minGain: Double?, val maxGain: Double?,
                         val iframes: List<List<Double>>, val streamId: String)
```

---

### Task 1: Project scaffold that builds an installable TV APK

**Files:**
- Create: `settings.gradle.kts`, `build.gradle.kts`, `gradle.properties`, `gradle/libs.versions.toml`, Gradle wrapper (9.8.2), `app/build.gradle.kts`, `app/src/main/AndroidManifest.xml`, `app/src/main/res/drawable/banner.xml`, `app/src/main/res/values/strings.xml`, `app/src/main/res/font/` (Vazirmatn Regular/Medium/Bold/ExtraBold from the OFL release), `app/src/main/java/com/alikarami/drooz/DroozApp.kt`, `AppGraph.kt` (empty for now), `ui/debug/MainActivity.kt` (shows «دی‌ال‌روز» centered).
- Test: `app/src/test/java/com/alikarami/drooz/SmokeTest.kt`

**Interfaces:**
- Produces: `applicationId = "com.alikarami.drooz"`, app label «دی‌ال‌روز», `DroozApp.graph: AppGraph`.

- [ ] **Step 1:** Generate wrapper: `gradle wrapper --gradle-version 9.8.2` (use cached `~/.gradle/wrapper/dists/gradle-8.14.3-all` gradle binary if no `gradle` on PATH: `~/.gradle/wrapper/dists/gradle-8.14.3-all/*/gradle-8.14.3/bin/gradle wrapper --gradle-version 9.8.2`).
- [ ] **Step 2:** Write the version catalog with the exact versions from the header. Plugins: `com.android.application`, `org.jetbrains.kotlin.plugin.compose`, `org.jetbrains.kotlin.plugin.serialization`, `com.google.devtools.ksp` (declared, applied in Plan 4). AGP 9 has built-in Kotlin support — do **not** apply `org.jetbrains.kotlin.android`; if the build says otherwise, follow the AGP error message.
- [ ] **Step 3:** Install compileSdk platform if missing: `$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager "platforms;android-37.0" "build-tools;37.0.0"` (accept whatever build-tools AGP asks for).
- [ ] **Step 4:** Manifest: `<uses-feature android:name="android.software.leanback" android:required="true"/>`, `<uses-feature android:name="android.hardware.touchscreen" android:required="false"/>`, INTERNET permission, `android:banner="@drawable/banner"`, `android:supportsRtl="true"`, MainActivity with `LEANBACK_LAUNCHER` + `LAUNCHER` intent filters, theme `@android:style/Theme.DeviceDefault.NoActionBar`.
- [ ] **Step 5:** Smoke test `assertEquals("com.alikarami.drooz", BuildConfig.APPLICATION_ID)` (enable `buildConfig = true`).
- [ ] **Step 6:** Run `./gradlew :app:testDebugUnitTest :app:assembleDebug` → BUILD SUCCESSFUL, `app/build/outputs/apk/debug/app-debug.apk` exists.
- [ ] **Step 7:** Commit `chore: scaffold Android TV project`.

### Task 2: Sanitized fixtures from the real site + hygiene guard

**Files:**
- Create: `scripts/capture_fixtures.sh`, `scripts/sanitize_fixture.py`, `app/src/test/resources/fixtures/{login_form,login_needs_mobile,login_wrong,login_success,home,list_new_films,search_pluribus,title_series_pluribus,title_film_ouatime,stream_film_ouatime}.html`
- Test: `app/src/test/java/com/alikarami/drooz/FixtureHygieneTest.kt`

**Interfaces:**
- Produces: fixture files above; placeholders `USERTOKEN` (download/stream user token, 39 hex chars in the original), user id `100001`, username `testuser`, phone hint `0912***0000`, m3u8 `key=KEY&expire=1800000000`, recaptchaPass `00000000000000000000000000000000` + `1800000000`, avatar `/img/…/avatar/100001_0.jpg.webp`.

- [ ] **Step 1:** `capture_fixtures.sh <cookiejar> <outdir>`: curl (UA + `-b scr=1 -b <jar>`) for `/`, `/archive/category/new-films/`, `/search/?s=pluribus`, `/post/series/5735053/pluribus/`, `/post/film/5744530/once-upon-a-time-in-the-middle-east/`, `/stream/371517/5744530/?lang=original&h=720`; logged-out login form with a fresh jar; wrong-password POST; and, using `~/.drtv-creds` with a fresh jar, the step-1 response (needs mobile) and step-2 success response (mobile read from env `DRTV_MOBILE`, never from a file in the repo). Pipes each through `sanitize_fixture.py`. The developer's existing session jar is at `$DRTV_JAR` (ask the user for it/its path; do not log in more often than this one capture).
- [ ] **Step 2:** `sanitize_fixture.py in out`: read the real token/user id/username from env (`DRTV_TOKEN`, `DRTV_UID`, `DRTV_USER`) and replace them and the regex classes below; fail (exit 1) if any env value still occurs after replacement.
  ```python
  RULES = [
    (r"(/json/m3u8/\d+/\d+/)[0-9a-f]{30,}(/master\.m3u8\?key=)[0-9a-f]+(&expire=)\d+", r"\1USERTOKEN\2KEY\g<3>1800000000"),
    (r"(/dln/)[0-9a-f]{30,}/", r"\1USERTOKEN/"),
    (r"(name='recaptchaPass' value=')[0-9a-f]{32}\d{10}", r"\g<1>000000000000000000000000000000001800000000"),
    (r"09\d\d\*\*\*\d{4}", "0912***0000"), (r"\d{4}\*\*\*09\d\d", "0000***0912"),
    (r"halt-(video|audio)-([0-9a-f]+)-\d+-", r"halt-\1-\2-100001-"),
    (r"(avatar/)\d+_\d+", r"\g<1>100001_0"),
  ]
  ```
- [ ] **Step 3:** Write `FixtureHygieneTest`: for every file in `fixtures/`, assert no match for `/dln/(?!USERTOKEN)[0-9a-f]{30,}/`, `/json/m3u8/\d+/\d+/(?!USERTOKEN)`, `key=(?!KEY)[0-9a-f]{8,}`, `09\d\d\*\*\*(?!0000)\d{4}`, `avatar/(?!100001_)`; assert `stream_film_ouatime.html` contains `USERTOKEN`.
- [ ] **Step 4:** Run capture + sanitize, then `./gradlew :app:testDebugUnitTest --tests '*FixtureHygieneTest'` → PASS. Additionally `grep -rE "<real token>|<real uid>|<real username>" app/src/test/resources` (values from env) prints nothing.
- [ ] **Step 5:** Commit `test: add sanitized site fixtures and hygiene guard`.

### Task 3: HTTP layer — persistent cookies, `scr=1`, typed failures

**Files:**
- Create: `site/SiteConfig.kt`, `site/SiteException.kt`, `site/http/PersistentCookieJar.kt`, `site/http/SiteHttp.kt`
- Test: `site/http/PersistentCookieJarTest.kt`, `site/http/SiteHttpTest.kt` (MockWebServer)

**Interfaces:**
- Produces:
  - `sealed class SiteException : Exception` with `HttpError(code: Int)`, `NetworkError(cause)`, `Blocked` (Cloudflare challenge), `SessionExpired`, `NotLoggedIn`, `ParseError(what: String)`.
  - `class PersistentCookieJar(file: File) : CookieJar` — `fun cookie(name: String): String?`, `fun clear()`; always adds `scr=1` to outgoing requests for the site host.
  - `class SiteHttp(baseUrl: HttpUrl, jar: PersistentCookieJar, client: OkHttpClient = default)` — `suspend fun get(path: String, requiresAuth: Boolean = true): SiteResponse`, `suspend fun postMultipart(path: String, fields: List<Pair<String,String>>): SiteResponse`, `suspend fun getFinalUrl(path: String): String` (follows redirects, returns final URL; used for `/dln/`). `data class SiteResponse(val finalPath: String, val body: String)`.

- [ ] **Step 1: Failing tests**
  ```kotlin
  @Test fun persistsCookiesAcrossInstances()       // set sf+cf via Set-Cookie, new jar on same file → cookie("cf") == value
  @Test fun alwaysSendsScrCookie()                  // server.takeRequest().getHeader("Cookie") contains "scr=1"
  @Test fun sendsChromeUserAgent()                  // header == SiteConfig.USER_AGENT
  @Test fun redirectToLoginOnAuthPageThrowsSessionExpired()  // 302 Location:/user/login/ for "/" → SessionExpired
  @Test fun loginPathDoesNotThrowSessionExpired()   // get("/user/login/", requiresAuth=false) returns body
  @Test fun non200ThrowsHttpError()                 // 500 → HttpError(500)
  @Test fun cloudflareChallengeThrowsBlocked()      // 403 body containing "cf-chl" or "Just a moment" → Blocked
  @Test fun jsRedirectStubWithoutScrIsNotReturnedAsPage() // body contains "setCookie(window.devicePixelRatio)" → ParseError("scr-stub")
  ```
- [ ] **Step 2:** Run `./gradlew :app:testDebugUnitTest --tests '*http*'` → FAIL (classes missing).
- [ ] **Step 3:** Implement. Jar stores cookies as JSON lines in the file, honours expiry, never persists `scr`. `SiteHttp` uses `Dispatchers.IO`, OkHttp `followRedirects(true)`; expiry = `requiresAuth && finalPath.startsWith("/user/login/")`.
- [ ] **Step 4:** Run → PASS.
- [ ] **Step 5:** Commit `feat(site): http layer with persistent cookies`.

### Task 4: Login — parser and two-step AuthService

**Files:**
- Create: `site/auth/LoginParser.kt`, `site/auth/AuthService.kt`, `site/auth/CredentialStore.kt`, `site/auth/KeystoreSecretBox.kt`
- Test: `site/auth/LoginParserTest.kt`, `site/auth/AuthServiceTest.kt`, `site/auth/CredentialStoreTest.kt`

**Interfaces:**
- Consumes: `SiteHttp`, `PersistentCookieJar` (Task 3).
- Produces:
  - `sealed interface LoginPage { object Form; data class NeedsMobile(val recaptchaPass: String, val mobileHint: String); object WrongCredentials; object Redirecting; data class Unknown(val alertText: String?) }`; `object LoginParser { fun parse(html: String): LoginPage }`
  - `sealed interface LoginResult { object Success; data class NeedsMobile(val hint: String); object WrongCredentials; data class Failed(val message: String) }`
  - `interface SecretBox { fun encrypt(plain: ByteArray): ByteArray; fun decrypt(cipher: ByteArray): ByteArray }`; `class KeystoreSecretBox : SecretBox` (AES/GCM/NoPadding, key alias `drooz_creds`, IV prepended).
  - `data class Credentials(val username: String, val password: String, val mobile: String?)`; `class CredentialStore(file: File, box: SecretBox) { fun save(c: Credentials); fun load(): Credentials?; fun clear() }`
  - `class AuthService(http: SiteHttp, jar: PersistentCookieJar, creds: CredentialStore)`: `val isLoggedIn: Boolean` (cookie `cf` present), `suspend fun login(username: String, password: String, mobile: String?): LoginResult` (saves creds on Success), `suspend fun relogin(): Boolean` (uses stored creds, keeps jar), `suspend fun logout()` (GET `/user/logout/`, clear jar except `sf`, clear creds).

- [ ] **Step 1: Failing tests**
  ```kotlin
  @Test fun formPage()            { assertEquals(LoginPage.Form, LoginParser.parse(fixture("login_form.html"))) }
  @Test fun needsMobilePage()     { val p = LoginParser.parse(fixture("login_needs_mobile.html")) as LoginPage.NeedsMobile
                                    assertEquals("000000000000000000000000000000001800000000", p.recaptchaPass)
                                    assertEquals("0912***0000", p.mobileHint) }
  @Test fun wrongCredentials()    { assertEquals(LoginPage.WrongCredentials, LoginParser.parse(fixture("login_wrong.html"))) }
  @Test fun success()             { assertEquals(LoginPage.Redirecting, LoginParser.parse(fixture("login_success.html"))) }
  @Test fun unknownPageYieldsFailedWithAlertText() // html with <div class='alert alert-danger'>حساب شما مسدود است</div> and no form → Unknown("حساب شما مسدود است")
  // AuthServiceTest (MockWebServer, enqueue fixtures):
  @Test fun twoStepLoginPostsMobileAndRecaptchaPass() // 1st POST fields username,password; 2nd adds recaptchaPass + mobile; Set-Cookie cf → Success, isLoggedIn
  @Test fun needsMobileWithoutMobileReturnsHint()     // mobile=null → LoginResult.NeedsMobile("0912***0000"), only one POST
  @Test fun wrongPasswordReturnsWrongCredentials()
  @Test fun reloginKeepsSfCookie()                     // sf before == sf after
  @Test fun reportsDeviceAfterSuccess()                // a GET to /json/device/?width=3840&height=2160&pixelRatio=1 is made
  // CredentialStoreTest with a fake XOR SecretBox:
  @Test fun roundTrips(); @Test fun fileDoesNotContainPlainPassword()
  ```
- [ ] **Step 2:** Run `--tests '*auth*'` → FAIL.
- [ ] **Step 3:** Implement. Text markers: wrong = «نام کاربری یا رمز عبور اشتباه», needs-mobile = input `name=mobile` + hidden `recaptchaPass` (hint from the mobile input's `placeholder`), redirecting = «در حال انتقال». Login POSTs are multipart to `/user/login/` with `requiresAuth=false`.
- [ ] **Step 4:** Run → PASS.
- [ ] **Step 5:** Commit `feat(site): two-step login and credential storage`.

### Task 5: Models, Persian text helpers, shared card parser, image URLs

**Files:**
- Create: `site/model/Models.kt` (exactly the block above), `site/parse/PersianText.kt`, `site/parse/CardParser.kt`, `site/ImageUrls.kt`
- Test: `site/parse/PersianTextTest.kt`, `site/ImageUrlsTest.kt`, `site/model/TitleRefTest.kt`

**Interfaces:**
- Produces: `object PersianText { fun digits(s: String): String /* ۰-۹ and ٠-٩ → 0-9 */; fun rating(s: String): Rating?; fun minutes(s: String): Int?; fun criticsScore(s: String): Int? }`; `fun TitleRef.Companion.fromPath(path: String): TitleRef?`; `object CardParser { fun parse(el: org.jsoup.nodes.Element): TitleCard? }` (an element that contains one title's link + metadata lines); `object ImageUrls { fun poster(hash: String, w: Int, h: Int): String; fun backdrop(hash: String, w: Int, h: Int): String; fun trailerThumb(hash: String, w: Int, h: Int): String }` → `"$BASE/img/${w}-${h}/$hash"` (and `/img/W-H/trailer/$hash`).

- [ ] **Step 1: Failing tests**
  ```kotlin
  @Test fun rating()   { assertEquals(Rating(7.9, 181000), PersianText.rating("۷.۹ از ۱۰ با ۱۸۱ هزار رای")) }
  @Test fun ratingPlain() { assertEquals(Rating(6.4, 999), PersianText.rating("۶.۴ از ۱۰ با ۹۹۹ رای")) }
  @Test fun ratingMillions() { assertEquals(Rating(9.3, 3_100_000), PersianText.rating("۹.۳ از ۱۰ با ۳.۱ میلیون رای")) }
  @Test fun minutes()  { assertEquals(141, PersianText.minutes("مدت : ۱۴۱ دقیقه")) }
  @Test fun critics()  { assertEquals(38, PersianText.criticsScore("امتیاز منتقدین : 38 / 100")) }
  @Test fun refFromPath() { assertEquals(TitleRef(TitleKind.SERIES, 5735053, "pluribus"), TitleRef.fromPath("/post/series/5735053/pluribus/")) }
  @Test fun refPathRoundTrip() // fromPath(ref.path) == ref
  @Test fun posterUrl() { assertEquals("https://robin.robert-redford.net/img/300-450/abc.jpg.webp", ImageUrls.poster("abc.jpg.webp", 300, 450)) }
  ```
- [ ] **Step 2:** Run → FAIL. **Step 3:** Implement (`CardParser` is exercised by Tasks 6's tests). **Step 4:** Run → PASS.
- [ ] **Step 5:** Commit `feat(site): models and text helpers`.

### Task 6: Page parsers — home, list, search, title

**Files:**
- Create: `site/parse/HomeParser.kt`, `site/parse/ListParser.kt`, `site/parse/SearchParser.kt`, `site/parse/TitleParser.kt`
- Test: `site/parse/HomeParserTest.kt`, `ListParserTest.kt`, `SearchParserTest.kt`, `TitleParserTest.kt`

**Interfaces:**
- Consumes: Models, `CardParser`, `PersianText` (Task 5).
- Produces: `object HomeParser { fun parse(html: String): HomePage }`, `object ListParser { fun parse(html: String): ListPage }`, `object SearchParser { fun parse(html: String): List<TitleCard> }`, `object TitleParser { fun parse(html: String, ref: TitleRef): TitleDetail; fun parseCatalog(streamJson: String): StreamCatalog }`. All throw `SiteException.ParseError(what)` when a required anchor is missing.

- [ ] **Step 1: Failing tests** (values from the real pages, 2026-10-28)
  ```kotlin
  // HomeParserTest
  @Test fun featured()  { val f = home.featured; assertEquals(6, f.size)
      assertEquals(5740731L, f[0].ref.postId); assertEquals("ac89a599f201a486ad141d4d2defd72f.jpg.webp", f[2].backdropHash) }
  @Test fun recentlyStreamed() { assertEquals(listOf(5744530L, 5743454L, 5732899L, 46629L, 1302463L, 5735053L),
      home.recentlyStreamed.map { it.ref.postId }) }
  @Test fun sections() { assertEquals(listOf("فیلم های برگزیده", "سریال های بروز شده"), home.sections.map { it.title }.take(2))
      val series = home.sections.first { it.title == "سریال های بروز شده" }
      assertEquals("/archive/series/", series.morePath); assertEquals(TitleKind.SERIES, series.items.first().ref.kind) }
  @Test fun filmCardMetadata() { val c = home.sections.flatMap { it.items }.first { it.ref.postId == 5744063L }
      assertEquals("Ali G: Who Iz I?", c.title); assertEquals(2026, c.year); assertEquals(89, c.durationMin)
      assertEquals(Rating(5.8, 1000), c.imdb); assertEquals(38, c.criticsScore); assertEquals(listOf("کمدی"), c.genres)
      assertEquals("Sacha Baron Cohen", c.director); assertTrue(c.synopsis!!.startsWith("روزنامه‌نگار هیپ‌هاپ")) }
  // ListParserTest
  @Test fun newFilmsPage1() { assertEquals(8, list.items.size); assertEquals(5744063L, list.items[0].ref.postId)
      assertEquals(1, list.page); assertEquals(478, list.lastPage) }
  // SearchParserTest
  @Test fun findsPluribusOnce() { val r = SearchParser.parse(fixture("search_pluribus.html"))   // poster + title links de-duplicated
      assertEquals(listOf(5735053L), r.map { it.ref.postId }) }   // sidebar "popular series" must NOT be included
  // TitleParserTest
  @Test fun seriesCatalogAllPlayed() { val d = pluribus; assertEquals(1, d.catalog.main.size)
      val eps = d.catalog.main[0].episodes; assertEquals((1..9).toList(), eps.map { it.number }); assertTrue(eps.all { it.played })
      assertEquals("325497", eps[0].streamId); assertEquals(listOf(480, 720, 1080), eps[0].videos.map { it.height })
      assertEquals(listOf(2, 6), eps[0].audios.map { it.channels }); assertEquals(listOf("fa", "en"), eps[0].subtitles.map { it.lang })
      assertTrue(d.catalog.dub.isEmpty()) }
  @Test fun seriesMetadata() { val d = pluribus; assertEquals("Pluribus", d.card.title); assertEquals(Rating(7.9, 181000), d.card.imdb)
      assertEquals("Apple TV+", d.channel); assertEquals(listOf("درام", "علمی-تخیلی"), d.card.genres)
      assertEquals(listOf(20835L, 4444536L, 5688281L, 5733846L), d.suggestions.take(4).map { it.postId })
      assertTrue(d.awards.any { it.contains("برنده بهترین بازیگر نقش اول زن") }) }
  @Test fun filmCatalogArrayFormWithDub() { val c = ouatime.catalog
      assertEquals("371517", c.main.single().episodes.single().streamId); assertEquals("372848", c.dub.single().episodes.single().streamId)
      assertEquals("dubbed-fa", c.dub.single().episodes.single().audios.single().lang); assertFalse(c.main.single().episodes.single().played) }
  @Test fun seriesObjectFormKeysAreSortedNumerically() // parseCatalog("""{"main":{"10":{"2":{..},"1":{..}},"2":{"1":{..}}},"subtitle":true}""") → seasons [2,10], episodes [1,2]
  @Test fun missingStreamJsonThrowsParseError()        // parse("<html></html>", ref) → ParseError("STREAM")
  ```
- [ ] **Step 2:** Run → FAIL.
- [ ] **Step 3:** Implement. `STREAM` is extracted with the regex `var STREAM=(\{.*?\});\s*\n` (DOTALL) and parsed with kotlinx `JsonElement` — seasons/episodes may be a `JsonArray` (films) or a `JsonObject` keyed by numeric strings (series); `played` absent ⇒ false. Search results are only the cards under the «فیلم و سریال» heading. Suggestions are `/post/` links after «آثار پیشنهادی». Trailer thumb hash from `/img/590-331/trailer/<hash>`.
- [ ] **Step 4:** Run → PASS.
- [ ] **Step 5:** Commit `feat(site): home, list, search and title parsers`.

### Task 7: Stream page parser

**Files:**
- Create: `site/parse/StreamPageParser.kt`
- Test: `site/parse/StreamPageParserTest.kt`

**Interfaces:**
- Produces: `object StreamPageParser { fun parse(html: String, streamId: String): StreamSession }` (relative URLs made absolute with `SiteConfig.BASE_URL`).

- [ ] **Step 1: Failing tests**
  ```kotlin
  @Test fun masterUrl()   { assertEquals("https://robin.robert-redford.net/json/m3u8/371517/5744530/USERTOKEN/master.m3u8?key=KEY&expire=1800000000&load", s.masterUrl) }
  @Test fun subtitles()   { assertEquals(listOf("Farsi", "English", "English + Farsi"), s.subtitles.map { it.label })
      assertEquals("https://robin.robert-redford.net/json/subtitle/582055/5744530/sub.srt?stream=371517&second=582056", s.subtitles[2].url) }
  @Test fun qualityLabels() { assertEquals(mapOf(2475 to "1080 [ 2.48 GB ]", 1100 to "720 [ 1.14 GB ]", 488 to "480 [ 553 MB ]"), s.qualityLabels) }
  @Test fun gains()       { assertEquals(841, s.gains.size); assertEquals(0.64, s.minGain); assertEquals(1.93, s.maxGain) }
  @Test fun iframes()     { assertEquals(3, s.iframes.size); assertEquals(listOf(0.0, 6.5, 8.208), s.iframes[0].take(3)) }
  @Test fun titleAndBackdrop() { assertEquals("Once Upon a Time in the Middle East", s.title)
      assertEquals("/img/1920-1080/ac89a599f201a486ad141d4d2defd72f.jpg.webp", s.backdropPath) }
  @Test fun pageWithoutGainsOrIframesStillParses() // strip those lines from the fixture → gains empty, minGain null, iframes empty
  ```
- [ ] **Step 2:** Run → FAIL. **Step 3:** Implement with targeted regexes over the inline script (`file: '…m3u8…'`, `tracks: [...]` objects `file:"…", label:'…'`, `qualityLabels:{…}`, `Gains = [[…]];`, `minGain=…;`, `IFrames[n] = […];`, `title: "…"`, `image: '…'`). **Step 4:** Run → PASS.
- [ ] **Step 5:** Commit `feat(site): stream page parser`.

### Task 8: SiteRepository with transparent re-login

**Files:**
- Create: `site/SiteRepository.kt`; wire `AppGraph` (`jar` file in `noBackupFilesDir/cookies.jsonl`, `CredentialStore` in `noBackupFilesDir/creds.bin` with `KeystoreSecretBox`, `SiteHttp`, `AuthService`, `SiteRepository`).
- Test: `site/SiteRepositoryTest.kt` (MockWebServer + fixtures)

**Interfaces:**
- Consumes: Tasks 3–7.
- Produces: `class SiteRepository(http: SiteHttp, auth: AuthService)` with `suspend fun home(): HomePage`, `suspend fun list(path: String, page: Int = 1): ListPage` (page 1 → `path`, page N → `"$path$N/"`, query strings kept after the page segment), `suspend fun search(query: String): List<TitleCard>`, `suspend fun title(ref: TitleRef): TitleDetail`, `suspend fun openStream(episode: Episode, ref: TitleRef, lang: String, height: Int): StreamSession` (GET `/stream/{streamId}/{postId}/?lang=$lang&h=$height`), `suspend fun setWatchlist(postId: Long, state: String)` (GET `/json/userWatchlist/$postId/$state/`), `suspend fun reportStreamError(streamId: String, event: String, msg: String)`.

- [ ] **Step 1: Failing tests**
  ```kotlin
  @Test fun homeParsesFixture()                // enqueue home.html → 6 featured
  @Test fun listPage3Path()                    // list("/archive/category/new-films/", 3) requests "/archive/category/new-films/3/"
  @Test fun listPageKeepsQuery()               // list("/archive/category/anime/?type=film", 2) requests "/archive/category/anime/2/?type=film"
  @Test fun openStreamRequestsCorrectPath()    // "/stream/371517/5744530/?lang=original&h=720"
  @Test fun relogsInOnceOnExpiredSession()     // 1st GET / → 302 /user/login/; login POSTs succeed; retried GET / → home.html; result parsed
  @Test fun givesUpAfterSecondExpiry()         // expiry again after relogin → throws NotLoggedIn, total login attempts == 1
  @Test fun noStoredCredentialsThrowsNotLoggedIn()
  ```
- [ ] **Step 2:** Run → FAIL. **Step 3:** Implement a private `suspend fun <T> authed(block: suspend () -> T): T` that catches `SessionExpired` once, calls `auth.relogin()`, retries; false/second expiry → `NotLoggedIn`. **Step 4:** Run → PASS.
- [ ] **Step 5:** Commit `feat(site): repository with transparent relogin`.

### Task 9: Debug UI — login → home → title → play, on the Streamer

Throwaway screens to prove the pipeline on the real device. Plans 2–4 replace them; keep them minimal (no styling work).

**Files:**
- Create: `ui/debug/DebugScreens.kt` (Login, Home, Title as `@Composable`s with tv-material `Button`s/`Text`, `LayoutDirection.Rtl`), `ui/debug/DebugViewModel.kt`, `ui/debug/DebugPlayerActivity.kt`; modify `MainActivity.kt`, `AndroidManifest.xml` (register player activity, landscape).
- Test: `ui/debug/DebugViewModelTest.kt` (fake repository)

**Interfaces:**
- Consumes: `AppGraph.repository`, `AppGraph.auth`.
- Produces: `DebugPlayerActivity.intent(context, session: StreamSession)` — plays `masterUrl` with Media3 `ExoPlayer` + `PlayerView`, adds the "Farsi" subtitle as `MediaItem.SubtitleConfiguration(mimeType = MimeTypes.APPLICATION_SUBRIP, language = "fa")`.

- [ ] **Step 1: Failing tests**
  ```kotlin
  @Test fun startsAtLoginWhenNoCookie()          // state == Login
  @Test fun needsMobileShowsHint()               // login → NeedsMobile("0912***0000") → state Login(mobileHint = "0912***0000")
  @Test fun successLoadsHome()                   // state == Home(page) with recentlyStreamed first
  @Test fun playFetchesStreamSessionForFirstUnplayedEpisode() // pluribus-like catalog with eps 1-3 played → openStream called with episode 4
  ```
- [ ] **Step 2:** Run → FAIL. **Step 3:** Implement. Home screen: one vertical list of section titles each followed by a `LazyRow` of text buttons (title); recently streamed first. Title screen: title, season/episode buttons («فصل ۱ · قسمت ۴ ✓»), «پخش» picks the first unplayed episode, `lang="original"`, height 1080. **Step 4:** Run → PASS.
- [ ] **Step 5: Install on the Streamer.** Ask the user to enable Developer options + Wireless debugging on the Google TV Streamer and give its IP:port; then `adb connect <ip:port>` and `./gradlew :app:installDebug`. Expected: app icon appears in Google TV's apps row.
- [ ] **Step 6: Manual verification with the user (report results honestly):**
  1. Log in on the TV (username/password, then mobile when asked). Expected: home lists the same «مشاهدات اخیر پخش آنلاین» titles as the website.
  2. Kill and reopen the app. Expected: no login screen (cookies persisted).
  3. Play a film and an episode. Expected: video starts within ~3 s, Farsi subtitles shown, seek works.
  4. **Spec §7.1 check:** have the user watch one not-yet-watched episode on the TV to the end, then re-open that series in the app (and on the laptop). Record whether the episode now shows ✓ (`played`). Write the outcome into `docs/superpowers/specs/2026-10-28-dlrooz-tv-design.md` §7.1 ("verified YYYY-MM-DD: yes/no").
- [ ] **Step 7:** Commit `feat(debug): end-to-end debug flow on device`; push `main` to `origin`.

---

## Self-review notes

- Spec §4.1–4.3 → Tasks 3, 4, 6, 7, 8. §7.4 re-login keeping `sf` → Tasks 4, 8. §7.5 fixtures → Task 2. Early §7.1 `played` verification → Task 9 Step 6. §5/§6/§7.2/§7.3 are Plans 2–4 by design.
- Types used by later tasks are all defined in the Models block or a task's Interfaces block.
