# Plan 2 — Browse UI Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the debug screens with the approved Netflix-style browse experience — login, home (icon rail + focus-following hero + rows), title page (numbered episode tiles), list grids and search — polished enough to use daily on the Google TV Streamer.

**Architecture:** Compose for TV screens driven by ViewModels that depend on small interfaces (`SiteApi`, `AuthApi`) so they are unit-tested with fakes on the JVM. A `TitleInfoRepository` caches title pages (dedup + concurrency limit) and a `BackdropResolver` picks the best wide image. Navigation-compose with type-safe routes. Playback still launches the Plan 1 `DebugPlayerActivity` (renamed `PlayerActivity`) until Plan 3.

**Tech Stack:** as Plan 1, plus Coil 3.6.3 (`coil-compose`, `coil-network-okhttp`), navigation-compose 2.10.2 type-safe routes (kotlinx-serialization), `androidx.tv:tv-material` 1.1.0 `NavigationDrawer`, Android `RecognizerIntent` for voice search.

**Spec:** `docs/superpowers/specs/2026-10-28-dlrooz-tv-design.md` §5 (screens), §7.3 (backdrops). Mockups approved: home = option B, title page = option A.

## Global Constraints

- Colors: background `#0B0B0F`, accent/progress `#E50914`, IMDb badge `#F5C518` on black text, watched ✓ `#46D369`, secondary text `#9EA2AD`. Font: bundled Vazirmatn for Persian; Latin titles in the system sans, `LayoutDirection.Ltr` text inside the RTL layout.
- Whole app `LayoutDirection.Rtl`; rows start at the right; the icon rail is on the right edge.
- Focus = scale 1.08 (cards) / 1.1 (tiles) + 3dp white outline + soft shadow, animated 150 ms; no focus jank (keys must never be swallowed by a stuck-loading screen).
- Persian strings in `res/values/strings.xml`; numbers inside Persian strings use Persian digits (`toPersianDigits`).
- Never fetch `/stream/` pages for metadata (spec anti-goal). Title pages for the hero are fetched only on focus dwell ≥ 300 ms, max 2 concurrent, cached 24 h in memory.
- Images: always request exact pixel size through `ImageUrls` (poster 240×360 px, landscape card 440×248 px, hero 1920×1080 px).
- Plan 1 deferred minor that bites here: ViewModels must not catch `CancellationException` (use a helper that rethrows it).
- Target device: Google TV Streamer, 1920×1080 UI at density 2.0 (960×540 dp).

## Review Focus

1. **Remote navigation dead-ends** — every screen must be fully operable with D-pad + OK + Back: from any card, Up/Down reaches the neighbouring row, → (toward the right edge) reaches the rail, Back on home exits only after a second press. *On-device checklist, Task 10.*
2. **Focus restoration** — Back from a title page returns focus to the exact card that opened it (row + index), not the first card. *Test `HomeViewModelTest.remembersFocusedCardPerRow` (Task 3) + on-device check (Task 10).*
3. **Slow / failing site** (≈25% dropped requests even with retries) — screens show cached/partial content with an inline retry, never a blank screen; a failed hero fetch keeps the poster-only hero. *Tests in Tasks 2, 3, 7.*
4. **Titles without metadata** (home poster-only items show slugs) — slug prettified immediately ("shaun-the-sheep…" → "Shaun The Sheep…"), replaced by the real title when the page loads. *Test `TitleTextTest.prettifiesSlug` (Task 1) + `HomeViewModelTest.heroUpgradesTitleWhenDetailLoads` (Task 3).*
5. **Session expired / NotLoggedIn mid-browse** → app navigates to Login once, preserving nothing sensitive; after login returns to Home. *Test `AppViewModelTest.notLoggedInRoutesToLogin` (Task 9).*

---

## File Structure

```
ui/theme/Theme.kt            DroozTheme (colors, typography, RTL provider)
ui/theme/Focus.kt            Modifier.droozFocus(scale) — scale+outline+shadow
ui/common/TitleText.kt       prettifySlug, toPersianDigits, episodeLabel(season, ep)
ui/common/Images.kt          Coil ImageLoader setup + PosterImage/BackdropImage composables
ui/common/Cards.kt           PosterCard, LandscapeCard, EpisodeTile
ui/common/States.kt          LoadingRow, ErrorInline(retry), safeLaunch helper (rethrows CancellationException)
data/SiteApi.kt              interface SiteApi + AuthApi (implemented by SiteRepository/AuthService adapters)
data/TitleInfoRepository.kt  cached TitleDetail by TitleRef, in-flight dedupe, Semaphore(2)
data/BackdropResolver.kt     best wide image per title (§7.3)
data/HomeRows.kt             row config (titles + list paths)
ui/home/HomeViewModel.kt, HomeScreen.kt, Hero.kt, Rail.kt
ui/title/TitleViewModel.kt, TitleScreen.kt
ui/list/ListViewModel.kt, ListScreen.kt
ui/search/SearchViewModel.kt, SearchScreen.kt
ui/login/LoginViewModel.kt, LoginScreen.kt
ui/AppViewModel.kt, ui/DroozNavHost.kt, ui/MainActivity.kt (replaces ui/debug/*), player/PlayerActivity.kt (moved from debug)
res/drawable/banner.png (320×180 launcher banner), res/values/strings.xml
```

### Shared interfaces (Task 2 creates; Tasks 3–9 consume)

```kotlin
interface SiteApi {
  suspend fun home(): HomePage
  suspend fun list(path: String, page: Int = 1): ListPage
  suspend fun search(query: String): List<TitleCard>
  suspend fun title(ref: TitleRef): TitleDetail
  suspend fun openStream(episode: Episode, ref: TitleRef, lang: String, height: Int): StreamSession
  suspend fun setWatchlist(postId: Long, state: String)
}
interface AuthApi { val isLoggedIn: Boolean; suspend fun login(username: String, password: String, mobile: String?): LoginResult }
class TitleInfoRepository(api: SiteApi, now: () -> Long = System::currentTimeMillis, ttlMs: Long = 24h) {
  suspend fun get(ref: TitleRef): TitleDetail          // cached, deduped, ≤2 concurrent
  fun peek(ref: TitleRef): TitleDetail?                 // cache only, no fetch
  fun remember(ref: TitleRef, backdropPath: String)     // record stream-page backdrop after playback
}
object BackdropResolver { fun resolve(ref: TitleRef, featured: Map<TitleRef, String>, played: Map<TitleRef, String>, detail: TitleDetail?): Backdrop }
sealed interface Backdrop { data class Wide(val url: String) : Backdrop; data class BlurredPoster(val url: String) : Backdrop; data object None : Backdrop }
data class HomeRowConfig(val title: String, val source: RowSource)   // RowSource = Recent | HomeSection(title) | ListPath(path)
```

---

### Task 1: Design system — theme, focus, text helpers, images

**Files:** Create `ui/theme/Theme.kt`, `ui/theme/Focus.kt`, `ui/common/TitleText.kt`, `ui/common/Images.kt`, `ui/common/Cards.kt`, `ui/common/States.kt`; modify `gradle/libs.versions.toml`, `app/build.gradle.kts` (Coil). Test: `ui/common/TitleTextTest.kt`, `ui/common/StatesTest.kt`.

**Interfaces — Produces:** `DroozTheme { }`, `Modifier.droozFocus(scale: Float = 1.08f)`, `fun prettifySlug(slug: String): String`, `fun String.toPersianDigits(): String`, `fun episodeLabel(season: Int, episode: Int): String` (= «فصل ۲ · قسمت ۷»), `fun CoroutineScope.safeLaunch(onError: (Throwable) -> Unit, block: suspend () -> Unit): Job` (rethrows `CancellationException`), composables `PosterCard(title, posterUrl, onClick, modifier)`, `LandscapeCard(title, subtitle, imageUrl, progress: Float?, onClick, modifier)`, `EpisodeTile(number, minutes, watched, progress: Float?, onClick, modifier)`.

- [ ] Step 1: tests — `prettifiesSlug`: `"shaun-the-sheep-the-beast-of-mossy-bottom"` → `"Shaun The Sheep The Beast Of Mossy Bottom"`; `persianDigits`: `"S2 E7 141"` → `"S۲ E۷ ۱۴۱"`; `episodeLabelFormat`: `episodeLabel(2, 7)` == `"فصل ۲ · قسمت ۷"`; `safeLaunchRethrowsCancellation`: cancelling the job does not call `onError`; `safeLaunchReportsErrors`: a thrown `SiteException.HttpError(500)` reaches `onError`.
- [ ] Step 2: run → FAIL. Step 3: implement; Coil `ImageLoader` built once in `AppGraph` with `OkHttpNetworkFetcherFactory(graph.mediaClient)`, memory cache 25% and disk cache 200 MB. Step 4: run → PASS.
- [ ] Step 5: commit `feat(ui): design system`.

### Task 2: Data layer for UI — SiteApi, TitleInfoRepository, BackdropResolver, row config

**Files:** Create `data/SiteApi.kt`, `data/TitleInfoRepository.kt`, `data/BackdropResolver.kt`, `data/HomeRows.kt`; modify `AppGraph.kt` (adapters `SiteRepository → SiteApi`, `AuthService → AuthApi`, a `TitleInfoRepository`). Test: `data/TitleInfoRepositoryTest.kt`, `data/BackdropResolverTest.kt`, `data/HomeRowsTest.kt`.

- [ ] Step 1: tests —
  - `cachesWithinTtl` (second `get` makes no API call), `refetchesAfterTtl`, `dedupesConcurrentRequests` (two concurrent `get` → one API call), `limitsToTwoConcurrentFetches` (5 different refs with a gated fake → max 2 in flight), `failureIsNotCached` (an exception then a success → second call fetches).
  - `BackdropResolverTest`: featured map wins → `Wide(ImageUrls.backdrop(hash,1920,1080))`; else played backdrop path; else detail trailer hash → `Wide(trailerThumb(...,1920,1080))`; else card poster → `BlurredPoster(poster(...,240,360))`; else `None`.
  - `HomeRowsTest.order`: titles exactly `["ادامه تماشا", "فیلم های برگزیده", "سریال های بروز شده", "فیلم های جدید", "سریال های جدید", "سریال های محبوب", "سریال های برتر IMDb", "دوبله فارسی", "مناسب کودکان"]`; list paths `/archive/category/new-tv-show/`, `/archive/playlist/show/most-popular-tv-shows/`, `/archive/playlist/show/imdb-top-250-tv-series/`, `/archive/category/dubbed-films/`, `/archive/category/safe-for-children/`.
- [ ] Step 2: run → FAIL. Step 3: implement (`Mutex` + `Map<TitleRef, Deferred>` for dedupe, `Semaphore(2)`). Step 4: run → PASS. Step 5: commit `feat(data): title cache, backdrops, home rows`.

### Task 3: HomeViewModel

**Files:** Create `ui/home/HomeViewModel.kt`. Test: `ui/home/HomeViewModelTest.kt` (fake `SiteApi`, `StandardTestDispatcher`, `advanceTimeBy`).

**Interfaces — Produces:** `data class HomeUiState(val rows: List<RowUi>, val hero: HeroUi?, val error: String?)`; `data class RowUi(val key: String, val title: String, val items: List<CardUi>, val loading: Boolean, val error: Boolean)`; `data class CardUi(val ref: TitleRef, val title: String, val posterUrl: String?, val subtitle: String?, val landscape: Boolean)`; `data class HeroUi(val ref: TitleRef, val title: String, val meta: List<String>, val chips: List<String>, val synopsis: String?, val backdrop: Backdrop)`; functions `onFocus(rowKey: String, index: Int)`, `loadRowIfNeeded(rowKey: String)`, `retryRow(rowKey)`, `focusedIndex(rowKey): Int`, `refresh()`.

- [ ] Step 1: tests —
  - `firstRowIsRecentlyStreamedLandscape` (row 0 key `recent`, `landscape = true`, titles prettified slugs).
  - `homeSectionsMappedInConfigOrder`.
  - `listRowsLoadLazily` (`list()` is not called until `loadRowIfNeeded("popular")`).
  - `heroShowsCardDataImmediately` (focus a list card → hero title/synopsis from the card, backdrop `BlurredPoster`, no `title()` call yet).
  - `heroFetchesDetailAfterDwell` (`title()` is called only after 300 ms; refocus within 300 ms cancels it).
  - `heroUpgradesTitleWhenDetailLoads` (slug title → real title, backdrop → `Wide`).
  - `heroFailureKeepsPosterHero`.
  - `remembersFocusedCardPerRow` (`onFocus("featured", 4)` → `focusedIndex("featured") == 4`).
  - `rowErrorIsInlineAndRetryable`.
- [ ] Step 2: run → FAIL. Step 3: implement with `safeLaunch`; chips: «دوبله» when the card or catalog has dub, «زیرنویس» when subtitles exist, «4K» when a 2160 variant exists (detail only). Step 4: run → PASS. Step 5: commit `feat(home): view model`.

### Task 4: Home screen UI — rail, hero, rows

**Files:** Create `ui/home/HomeScreen.kt`, `ui/home/Hero.kt`, `ui/home/Rail.kt`; consume Task 1 cards.

- [ ] Step 1: implement the layout as approved (mockup B): `NavigationDrawer` from tv-material on the right (collapsed icons; expanded labels «جستجو، خانه، سریال‌ها، فیلم‌ها، لیست من، تنظیمات»); hero occupies the top 60% with a crossfading `AsyncImage` (300 ms) behind right-to-left and bottom gradients; rows in a `LazyColumn` (first row landscape 16:9, 220 dp wide; others posters 120×180 dp); each `LazyRow` uses `Modifier.focusRestorer()` and scrolls to `focusedIndex`; app starts with focus on row 0, card 0; Back on home → first press toasts «برای خروج دوباره بازگشت را بزنید», second within 2 s finishes.
- [ ] Step 2: build, install, screenshot via `adb exec-out screencap` and compare against the mockup (hero + first rows visible, RTL order, focus ring). Fix visual deviations.
- [ ] Step 3: commit `feat(home): Netflix-style home screen`.

### Task 5: TitleViewModel

**Files:** Create `ui/title/TitleViewModel.kt`. Test: `ui/title/TitleViewModelTest.kt`.

**Interfaces — Produces:** `data class TitleUiState(val detail: TitleDetail?, val primaryLabel: String, val primaryEpisode: Episode?, val seasons: List<Int>, val selectedSeason: Int, val episodes: List<EpisodeUi>, val initialFocusEpisode: Int?, val dubAvailable: Boolean, val dubSelected: Boolean, val inWatchlist: Boolean?, val error: String?)`; `data class EpisodeUi(val episode: Episode, val label: String, val minutes: Int?, val watched: Boolean)`; `fun selectSeason(n)`, `fun toggleDub()`, `fun toggleWatchlist()`, `fun play(episode: Episode? = null)` → emits `PlayRequest(episode, ref, lang)`.

- [ ] Step 1: tests —
  - Labels:
    - `primaryLabelNotStarted` → `"پخش قسمت ۱"`;
    - `primaryLabelContinue` (eps 1–3 played of S1 with S2 present) → `"ادامه فصل ۱ قسمت ۴"`;
    - `primaryLabelAllWatched` → `"تماشای دوباره"`;
    - `filmLabel` → `"پخش"`.
  - Selection:
    - `crossesSeasonBoundary` (all of S1 played → `"ادامه فصل ۲ قسمت ۱"`, `selectedSeason == 2`);
    - `initialFocusIsNextUnwatched`.
  - Dub:
    - `dubHiddenWithoutDub`;
    - `dubSelectionPlaysDubStream` (toggle → `PlayRequest.lang == "dubbed-fa"` and episode from `catalog.dub`).
  - `watchlistToggleCallsSiteOnce`.
- [ ] Step 2: run → FAIL. Step 3: implement. Step 4: run → PASS. Step 5: commit `feat(title): view model`.

### Task 6: Title screen UI

**Files:** Create `ui/title/TitleScreen.kt`.

- [ ] Step 1: implement mockup A:
  - full-bleed backdrop (`BackdropResolver`) with right/bottom gradients;
  - title (LTR), meta line (IMDb badge, year, channel/duration, genres, season count), chips, awards line «🏆 …», synopsis (4 lines), creator/director + stars;
  - buttons: primary (initial focus), «لیست من» (♡/♥), segmented «زبان اصلی | دوبله» when available;
  - season tabs, then `EpisodeTile` row (✓ green when watched, number large, minutes small) focused at `initialFocusEpisode`;
  - second tab «آثار پیشنهادی» with poster row (opens title).
- [ ] Step 2: on-device screenshot check with Pluribus (all ✓, «تماشای دوباره») and Once Upon a Time in the Middle East (dub switch visible). Step 3: commit `feat(title): title page`.

### Task 7: List grid screen

**Files:** Create `ui/list/ListViewModel.kt`, `ui/list/ListScreen.kt`. Test: `ui/list/ListViewModelTest.kt`.

- [ ] Step 1: tests — `loadsFirstPage`, `loadsNextPageWhenNearEnd` (`onFocusIndex(items.size - 6)` → `list(path, 2)`), `stopsAtLastPage`, `doesNotDoubleLoad` (two near-end focus events while page 2 is in flight → one call), `pageErrorKeepsItemsAndShowsRetry`.
- [ ] Step 2: run → FAIL. Step 3: implement; the grid is a `LazyVerticalGrid` with 7 poster columns, focus-following hero strip at the top reusing `Hero` (compact height 35%). Step 4: run → PASS. Step 5: commit `feat(list): paginated grid`.

### Task 8: Search (keyboard + voice)

**Files:** Create `ui/search/SearchViewModel.kt`, `ui/search/SearchScreen.kt`. Test: `ui/search/SearchViewModelTest.kt`.

- [ ] Step 1: tests — `debouncesQueries` (typing "p","pl","plu" within 400 ms → one `search("plu")`), `ignoresShortQueries` (< 2 chars → no call, empty results), `latestQueryWins` (slow "pl" result arriving after "plu" is discarded), `voiceResultRunsSearchImmediately` (`onVoiceResult("pluribus")` → `search` without debounce).
- [ ] Step 2: run → FAIL. Step 3: implement; the screen has a text field (system TV keyboard, `KeyboardType.Text`, autocorrect off, D-pad Up/Down leaves the field) with a mic button launching `RecognizerIntent.ACTION_RECOGNIZE_SPEECH` (`EXTRA_LANGUAGE = "fa-IR"`, `EXTRA_LANGUAGE_PREFERENCE`, accepts English too) via `rememberLauncherForActivityResult`; results reuse the list grid. Step 4: run → PASS. Step 5: commit `feat(search): keyboard and voice search`.

### Task 9: Login screen, app routing, navigation; retire debug UI

**Files:** Create `ui/login/LoginViewModel.kt`, `ui/login/LoginScreen.kt`, `ui/AppViewModel.kt`, `ui/DroozNavHost.kt`, `ui/MainActivity.kt`; move `ui/debug/DebugPlayerActivity.kt` → `player/PlayerActivity.kt` (logging gated by `BuildConfig.DEBUG` — Plan 1 deferred minor); delete `ui/debug/DebugScreens.kt`, `DebugViewModel.kt` and its test (port the debug launch extras `debug_user/pass/mobile/play` into `MainActivity`, debug builds only); update the manifest. Test: `ui/login/LoginViewModelTest.kt`, `ui/AppViewModelTest.kt`.

- [ ] Step 1: tests —
  - `LoginViewModelTest`:
    - `needsMobileShowsHintAndKeepsFields`;
    - `wrongCredentialsMessage` (= «نام کاربری یا رمز عبور اشتباه است»);
    - `networkErrorMessage` (= «اتصال به سایت برقرار نشد؛ دوباره تلاش کنید»);
    - `successEmitsLoggedIn`.
  - `AppViewModelTest`:
    - `startsAtLoginWhenLoggedOut`;
    - `startsAtHomeWhenLoggedIn`;
    - `notLoggedInRoutesToLogin` (any screen reporting `NotLoggedIn` → route Login exactly once).
- [ ] Step 2: run → FAIL. Step 3: implement. Routes are `@Serializable` objects/classes: `Login`, `Home`, `Title(kind, postId, slug)`, `ListRoute(title, path)`, `Search`. The title page's play → `PlayerActivity.intent(...)` after `openStream` (lang from dub switch, height 1080). Step 4: run all tests → PASS.
- [ ] Step 5: commit `feat(app): login, navigation; remove debug UI`.

### Task 10: Launcher banner + on-device acceptance

**Files:** Create `res/drawable-xhdpi/banner.png` (320×180, dark background, red bar, «دی‌ال‌روز» wordmark), replace `banner.xml` reference.

- [ ] Step 1: install on the Streamer (`adb connect` the TV, `./gradlew :app:installDebug`).
- [ ] Step 2: acceptance walk-through, with screenshots saved under the scratchpad:
  1. App launches to Home with focus on the first "ادامه تماشا" card within 3 s of a warm start.
  2. Moving across rows updates the hero within ~0.5 s with no stutter.
  3. Rail opens with →; every rail item navigates.
  4. Opening a title and pressing Back restores focus to the same card.
  5. A title page's primary button plays the right episode.
  6. Search by keyboard ("pluribus") and by voice works.
  7. A list grid paginates.
  8. Double-Back exits.

  Record each result honestly; failures become fixes in this task (TDD where logic is involved).
- [ ] Step 3: commit `chore: launcher banner`; merge to `main` per the finishing skill.

---

## Self-review notes

- Spec §5.1 → Tasks 3, 4; §5.2 → 5, 6; §5.3 → 8; §5.4 → 9; §5.5 Settings is Plan 4 (the rail item shows «به‌زودی» until then); §7.3 → Task 2. §7.2 smart Continue Watching (episode + progress) is Plan 4 — in this plan, row 0 shows the server's recently streamed titles.
- Plan 1 deferred minors addressed here: CancellationException handling (Task 1 `safeLaunch`), player logging gating (Task 9).
- Playlist list pages (`/archive/playlist/show/...`) are assumed to use `.postMeta` cards like category pages; Task 10 step 2.2 verifies on device — if a row is empty, capture a fixture from the TV session and extend `ListParser` with a failing test first.
