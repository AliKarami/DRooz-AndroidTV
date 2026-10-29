# Plan 4: Watch State, Continue Watching, My List, Settings, Polish

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The app remembers what you watched on the TV. The title page and «ادامه تماشا» use that memory, so one press continues a series. «لیست من» and «تنظیمات» work, and the player's known rough edges are fixed.

**Architecture:**
- Rule core: one pure function, `NextUp`, decides "what plays next and how far along it is" from a title's catalog plus the local `WatchStore`. The title page and the Continue Watching builder both call it.
- Continue Watching: the row merges the server's recently-streamed list with local history and resolves each title through the cached `TitleInfoRepository`.
- My List: reuses the list screen on `/user/watchlist/`.
- Settings: a new screen over `PlayerPrefs`, `TitlePrefs` and auth.

**Tech Stack:** Kotlin, Compose for TV (`androidx.tv:tv-material`), DataStore, coroutines; JUnit4 with kotlinx-coroutines-test (the repo's existing view-model test style with hand-written fakes).

**Spec:** `docs/superpowers/specs/2026-10-28-dlrooz-tv-design.md`:
- §5.1 «ادامه تماشا»
- §5.2 primary button, ✓ and partial bar
- §5.5 Settings
- §7.1 watch state
- §7.2 Continue watching

## Global Constraints

- **UI and copy:**
  - Persian RTL UI; numbers in Persian strings use Persian digits (`toPersianDigits()`).
  - Title names stay in their original language.
  - Copy computed in view models lives in `UiStrings`; static screen copy lives in `strings.xml`.
- **Watched ✓** = server `played` OR local `WatchEntry.finished`. Local wins until the server catches up (§7.1).
- **Resume and partial progress:** resume only after 30 s and before 90 % (`resumePositionMs`, `isFinished` in `player/PlaybackRules.kt`). Reuse these; do not redefine thresholds.
- **Network:**
  - Never open a `/stream/` page except on an explicit Play.
  - Continue Watching resolves titles with title pages only, through `TitleInfoRepository` (cached, at most 2 concurrent).
- **Privacy:** no logging in release builds; never log URLs, cookies or credentials.
- **Logout** goes through `AuthService.logout()`, which keeps the `sf` device cookie (the site allows 3 devices per account).
- **Errors:** a disk or network failure in watch state, prefs or settings never crashes a screen; it degrades to "no local state" or a Persian error state.
- **Tests:** new logic gets JVM unit tests with fakes, in the existing style (see `ui/title/TitleViewModelTest.kt` and `ui/home/HomeViewModelTest.kt`). There is no Compose UI test setup; UI wiring is verified by building, and on the TV at the end.
- **Run tests with:**
  `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew :app:testDebugUnitTest`
  The full suite must stay green; `:app:assembleDebug` must build.

## Designer's calls (rulings made before execution; spec silent or open)

- **Continue Watching order.** The server's recently-streamed order comes first; titles found only locally follow, newest first.
  - Why: every TV play opens a stream page, so the server list already includes TV plays, and it is the only cross-device order.
  - Cost if wrong: ordering tweak.
- **A fully watched series is dropped** from Continue Watching. No «فصل جدید به زودی» card.
  - Cost if wrong: one small card variant.
- **A film is dropped** once finished locally or played on the server. A server-listed film with no local position shows without a progress bar.
- **Cards show immediately and fill in.** Continue Watching cards appear from the server list at once with no subtitle. When a title's detail arrives, the card gains its episode label and progress, or is dropped if done.
  - Pressing OK before then opens the title page; afterwards OK plays immediately. Long-press OK always opens the title page (spec §5.1).
- **My List** is `ListScreen` pointed at `/user/watchlist/`.
  - The page could not be captured as a fixture (the dev session has expired, and we do not log in again because of the device limit), so it is assumed to use the site's standard list markup.
  - Verified on the TV at the end. An empty or unparseable page shows «لیست شما خالی است».
- **New global default «زبان پیش‌فرض: دوبله فارسی (در صورت وجود)»**, off by default. It is used only for titles with no saved per-title choice.
- **Clear cache** clears the image disk cache, `HomePageCache` and `TitleInfoRepository`. It never touches watch state, prefs or login.

## Review Focus

1. **Local state across both catalogs.** A series with both original and dubbed catalogs, partly watched in one: ✓ and progress follow the `streamId` the player saved. A dubbed episode with its own `streamId` shows its own state; nothing is attributed by season/episode number alone.
2. **Watched elsewhere.** A series watched only on the laptop (server `played` on S1E1–E3, no local entries): primary is «ادامه فصل ۱ قسمت ۴», and Continue Watching shows «فصل ۱ · قسمت ۴».
3. **Unresolvable title.** A Continue Watching title whose detail fetch fails keeps its card (OK opens the title page). Other cards still resolve; the row never errors as a whole.
4. **Logout.** Logout clears session caches and goes to Login with the back stack cleared. Back from Login does not return to Settings or Home.
5. **Return from the player.** Coming back from the player refreshes local state on the title page and on Home (progress bar moves, ✓ appears) even when the server page is unchanged.

---

### Task 1: `NextUp` — what plays next, from catalog + local state

**Files:**
- Create: `app/src/main/java/com/alikarami/drooz/data/NextUp.kt`
- Test: `app/src/test/java/com/alikarami/drooz/data/NextUpTest.kt`

**Interfaces:**
- **Consumes:**
  - `Season` and `Episode` from `site/model/Models.kt`.
  - `WatchEntry` from `data/WatchStore.kt`.
  - `isFinished` and `resumePositionMs` from `player/PlaybackRules.kt`.
- **Produces:**
  ```kotlin
  /** Local and server state of one episode. progress is in 0..1, non-null only while resumable (not watched). */
  data class EpisodeState(val watched: Boolean, val progress: Float?, val resumeMs: Long, val remainingMin: Int?)
  fun episodeState(ep: Episode, entry: WatchEntry?): EpisodeState

  sealed interface NextUp {
      val episode: Episode
      /** Nothing watched yet: the first episode. */
      data class Start(override val episode: Episode) : NextUp
      /** An episode is part-watched locally (the most recently updated one wins). */
      data class Resume(override val episode: Episode, val state: EpisodeState) : NextUp
      /** The episode after the last watched one, across seasons. */
      data class Next(override val episode: Episode) : NextUp
      /** Everything watched: offers the first episode again. */
      data class Again(override val episode: Episode) : NextUp
  }
  /** null when seasons has no episodes. entries are keyed by streamId. */
  fun nextUp(seasons: List<Season>, entries: Map<String, WatchEntry>): NextUp?
  ```

**Decisions:**
- **Episode order** is seasons by number, then episodes by number (the same order as `nextEpisode` in PlaybackRules).
- **`watched`** = `ep.played || entry?.finished == true || isFinished(entry.positionMs, entry.durationMs)`.
- **Progress fields** are set only when `resumeMs > 0` and the episode is not watched:
  - `resumeMs = resumePositionMs(entry.positionMs, entry.durationMs, entry.finished)`, else 0.
  - `progress = positionMs / durationMs`.
  - `remainingMin = ceil((durationMs - positionMs) / 60000)`.
- **`nextUp` precedence:**
  1. The resumable episode with the greatest `updatedAt` → Resume.
  2. If nothing is watched → Start(first).
  3. Otherwise take the last watched episode in order: if an episode follows it → Next(that one); if it is the final episode → Again(first).

- [ ] **Step 1: Write the failing tests** (`NextUpTest`). Helpers: `ep(s, n, played = false)` with `streamId = "s${s}e$n"`, and `entry(id, pos, dur, updatedAt, finished = false)`.
  - **`nothingWatched_startsAtFirst`:** S1E1..E3 → `Start(S1E1)`.
  - **`serverPlayed_nextAfterLastWatched_crossesSeasons`:** S1E1..E2 played, S2E1 exists → `Next(S2E1)`.
  - **`gapsDoNotMatter_lastWatchedWins`:** E1 and E3 played, E2 not → `Next(E4)`.
  - **`localFinishedCountsAsWatched`:** no server played; entry for E1 with `finished = true` → `Next(E2)`, and `episodeState(E1, entry).watched == true`.
  - **`resumeBeatsNext_mostRecentResumable`:**
    - Input: E1 played; E2 at 600 000/2 400 000 with updatedAt 5; E3 at 100 000/2 400 000 with updatedAt 9.
    - Expect `Resume(E3)` with `state.progress ≈ 0.0417` and `state.remainingMin == 39`.
  - **`underThirtySeconds_isNotResumable`:** E1 at 20 000/2 400 000, nothing played → `Start(E1)`; `episodeState(E1, entry).progress == null`.
  - **`ninetyPercent_isWatchedNotResumable`:** E1 at 2 200 000/2 400 000 (not flagged finished) → watched, `Next(E2)`.
  - **`allWatched_again`:** all played → `Again(first)`.
  - **`emptySeasons_null`:** `nextUp(emptyList(), emptyMap()) == null`.
  - **`film_resume`:** one Season(1) with E1 at 1 800 000/7 440 000 → `Resume`, `remainingMin == 94`.
- [ ] **Step 2: Run them; they should fail.** Run `./gradlew :app:testDebugUnitTest --tests '*NextUpTest'`. Expected: FAIL to compile (unresolved `nextUp`).
- [ ] **Step 3: Implement `NextUp.kt`** per the Interfaces and Decisions above.
- [ ] **Step 4: Run them; they should pass.** Same command. Expected: PASS, 10 tests.
- [ ] **Step 5: Commit** with `feat(data): NextUp rule for local + server watch state`.

---

### Task 2: Title page uses local watch state

**Files:**
- Modify:
  - `ui/title/TitleViewModel.kt` (constructor and `build()`)
  - `ui/title/TitleScreen.kt` (`Episodes` passes progress; factory)
  - `data/TitlePrefs.kt` (global default dub)
  - `ui/common/UiStrings.kt`
- Test: `app/src/test/java/com/alikarami/drooz/ui/title/TitleViewModelTest.kt`

**Interfaces:**
- **Consumes:** `nextUp` and `episodeState` (Task 1); `WatchStore.all()`.
- **Produces, for Task 6:**
  - `TitlePrefs.defaultDub(): Boolean` (default false) and `TitlePrefs.setDefaultDub(Boolean)`.
  - `UiStrings.continueFilm(remainingMin: Int): String` = «ادامه (۹۴ دقیقه باقی‌مانده)».
  - `UiStrings.remaining(min: Int): String` = «۹۴ دقیقه باقی‌مانده» (Task 3 uses it).
- **Changed:**
  - `TitleViewModel(ref, api, titleInfo, prefs, watchStore: WatchStore)`.
  - `EpisodeUi` gains `progress: Float?`.
  - `TitleUiState.primaryEpisode` stays as is; the player keeps resuming from the store itself.

**Decisions:**
- **Loading local entries:** the view model loads `watchStore.all()` filtered to `refPath == ref.path` into `Map<streamId, WatchEntry>`. It does this:
  - in `load()`, before the first publish that has detail;
  - on every `onResume()`, even when `playedSinceLoad` is false; the player writes locally even when the server page is unchanged.
  - A store exception means an empty map.
- **Primary label** comes from `nextUp` over the selected catalog (main or dub):
  - Start → series: `playEpisode`; film: `FILM`.
  - Resume → series: `continueWatching(s, e)`; film: `continueFilm(remainingMin)`.
  - Next → `continueWatching(s, e)`.
  - Again → `WATCH_AGAIN`.
- **Tiles:** `EpisodeUi.watched = episodeState(...).watched` and `EpisodeUi.progress = state.progress`.
- **Initial focus:** `initialFocusEpisode` is the NextUp episode's number when it lies in the selected season; otherwise the first unwatched episode in that season.
- **Default dub:** when no per-title choice is saved, `dub = prefs.defaultDub() && d.catalog.dub.isNotEmpty()`.
- **Layout:** `TitleScreen`'s `Episodes` passes `e.progress` to `EpisodeTile`; it currently passes `null`.

- [ ] **Step 1: Write the failing tests** in `TitleViewModelTest`. Add a `FakeWatchStore(entries: MutableList<WatchEntry>)` implementing `WatchStore`.
  - **`localFinished_marksTileWatched_andPrimaryMovesOn`:**
    - Input: E1 not played on the server; local entry for E1 finished.
    - Expect: `episodes[0].watched`; `primaryLabel == UiStrings.continueWatching(1, 2)`.
  - **`localPartial_showsProgress_andContinuesThatEpisode`:**
    - Input: E2 at 600 000/2 400 000.
    - Expect: `episodes[1].progress ≈ 0.25`; `primaryEpisode == E2`; `initialFocusEpisode == 2`.
  - **`film_resume_labelShowsRemainingMinutes`:**
    - Input: film entry at 1 800 000/7 440 000.
    - Expect: `primaryLabel == "ادامه (۹۴ دقیقه باقی‌مانده)"`.
  - **`onResume_rereadsLocalState_withoutServerChange`:**
    - Steps: load, add a finished entry to the fake, call `onResume()` without a play.
    - Expect: the tile becomes watched, and `api.title` was not called again.
  - **`watchStoreFailure_pageStillShows`:** the fake's `all()` throws an IOException; detail still shows, nothing is watched locally.
  - **`defaultDub_appliesOnlyWithoutSavedChoice`:**
    - Expect: with `defaultDub = true` and no saved value, `dubSelected == true`.
    - Expect: with a saved `false`, `dubSelected == false`.
  - **Existing tests:** update their construction to pass an empty `FakeWatchStore`, and add `defaultDub` to `FakePrefs`.
- [ ] **Step 2: Run them; they should fail.** Run `--tests '*TitleViewModelTest'`. Expected: compile or assertion failures in the new tests.
- [ ] **Step 3: Implement.**
  - The view model and `UiStrings` changes as decided above.
  - `DataStoreTitlePrefs.defaultDub` reads `booleanPreferencesKey("default_dub")`.
  - Update `TitleViewModelFactory` to pass `graph.watchStore`.
  - Pass progress to the tiles.
- [ ] **Step 4: Run them; they should pass.** Run `--tests '*TitleViewModelTest'`, then the full suite. Expected: PASS.
- [ ] **Step 5: Commit** with `feat(title): local watch state drives ✓, progress and the primary button`.

---

### Task 3: Continue Watching builder

**Files:**
- Create: `app/src/main/java/com/alikarami/drooz/data/ContinueWatching.kt`
- Test: `app/src/test/java/com/alikarami/drooz/data/ContinueWatchingTest.kt`

**Interfaces:**
- **Consumes:**
  - `PosterRef` (server recently streamed) and `WatchEntry` (local).
  - `TitleRef.fromPath`, `nextUp` (Task 1).
  - `UiStrings.remaining` (Task 2) and `episodeLabel` (`ui/common/TitleText.kt`).
- **Produces:**
  ```kotlin
  /** One «ادامه تماشا» card. play is null until the title's detail resolved; then OK plays it directly. */
  data class ContinueItem(
      val ref: TitleRef,
      val posterHash: String?,
      val subtitle: String?,
      val progress: Float?,
      val play: ContinuePlay?,
  )
  data class ContinuePlay(val streamId: String)

  object ContinueWatching {
      /** Titles in row order: server order, then local-only titles newest first; de-duplicated by postId. */
      fun order(server: List<PosterRef>, local: List<WatchEntry>): List<Pair<TitleRef, String?>>
      /**
       * The card for a resolved title, or null when it's done (series fully watched, film watched).
       * dub selects the catalog.
       */
      fun resolve(ref: TitleRef, posterHash: String?, detail: TitleDetail, entries: Map<String, WatchEntry>, dub: Boolean): ContinueItem?
      /** The card before its detail is known. */
      fun pending(ref: TitleRef, posterHash: String?): ContinueItem
  }
  ```

**Decisions:**
- **`order`:**
  - Local entries whose `refPath` does not parse via `TitleRef.fromPath` are skipped.
  - De-duplication is by `postId`; the first occurrence wins.
  - Local-only titles are ordered by their newest entry's `updatedAt`.
- **`resolve`** runs `nextUp` on the chosen catalog:
  - Again → null.
  - Series Start or Next → subtitle `episodeLabel(s, e)`, no progress.
  - Series Resume → `episodeLabel` plus progress.
  - Film Resume → subtitle `UiStrings.remaining(min)` plus progress.
  - Film Start → subtitle null.
  - The card's `play` is the NextUp episode's `streamId`.
- **`pending`:** subtitle null, progress null, `play` null.

- [ ] **Step 1: Write the failing tests:**
  - **`order_serverFirst_thenLocalOnlyNewestFirst_dedupByPostId`:**
    - Input: server [A, B]; local entries for B (updatedAt 50), C (10) and D (30).
    - Expect: [A, B, D, C].
  - **`order_skipsUnparseableRefPath`.**
  - **`resolve_seriesWatchedOnServer_showsNextEpisodeLabel`:** S1E1..E3 played → subtitle «فصل ۱ · قسمت ۴», play = E4's streamId, progress null.
  - **`resolve_seriesResume_hasProgress`.**
  - **`resolve_fullyWatchedSeries_isDropped`:** returns null.
  - **`resolve_filmResume_remainingMinutes`:** subtitle «۹۴ دقیقه باقی‌مانده».
  - **`resolve_filmPlayedOnServer_isDropped`:** returns null.
  - **`resolve_usesDubCatalogWhenDub`:**
    - Input: dub catalog with different streamIds; the local entry is on the dub E1.
    - Expect: Resume on dub E1.
- [ ] **Step 2: Run them; they should fail.** Run `--tests '*ContinueWatchingTest'`. Expected: FAIL (unresolved).
- [ ] **Step 3: Implement** per the Interfaces and Decisions above.
- [ ] **Step 4: Run them; they should pass.** Expected: PASS, 8 tests.
- [ ] **Step 5: Commit** with `feat(data): Continue Watching ordering and per-title resolution`.

---

### Task 4: Home «ادامه تماشا» row: resolve, play on OK, title on long-press

**Files:**
- Modify:
  - `ui/home/HomeViewModel.kt`
  - `ui/home/HomeScreen.kt` (factory, card click/long-click, play events)
  - `ui/common/Cards.kt` (`LandscapeCard` gains `onLongClick: (() -> Unit)? = null`)
  - `ui/DroozNavHost.kt` (start the player from Home)
- Test: `app/src/test/java/com/alikarami/drooz/ui/home/HomeViewModelTest.kt`

**Interfaces:**
- **Consumes:**
  - `ContinueWatching` (Task 3); `WatchStore`.
  - `TitlePrefs.dubSelected` and `TitlePrefs.defaultDub` (Task 2).
  - `PlayerArgs(ref, streamId, dub)` and `PlayerActivity.intent(context, args)`.
- **Produces:**
  - `HomeViewModel(api, titleInfo, dwellMs, homeCache, watchStore: WatchStore? = null, titlePrefs: TitlePrefs? = null)`. The new parameters are nullable so existing tests compile; with no store, the row behaves as today.
  - `CardUi.progress: Float? = null`.
  - `fun HomeViewModel.activate(rowKey: String, index: Int): HomeAction`, with
    `sealed interface HomeAction { data class Play(val args: PlayerArgs) : HomeAction; data class Open(val ref: TitleRef) : HomeAction }`.
    It returns Play for a resolved Continue Watching card and Open for everything else.

**Decisions:**
- **Building the recent row** (`RowSource.Recent`) in `applyHome`:
  1. Read `watchStore.all()`; a failure means an empty list.
  2. Build `ContinueWatching.order(...)`.
  3. Show `pending` cards at once.
  4. Resolve each title through `titleInfo.get(ref)` in its own `safeLaunch`. The repository already limits fetches to 2 concurrent.
  5. Resolution replaces that card's subtitle and progress, or removes the card when `resolve` returns null.
  6. A failed fetch leaves the pending card.
  7. `dub` for each title = saved per-title choice, else `defaultDub`, else false.
- **Card image:** keep today's logic (featured backdrop, then poster). Additionally, after resolution use `BackdropResolver.resolve(...)` with `Backdrop.Wide` sized 440×248 when it is Wide.
- **`onResume()`** always rebuilds the recent row from local state and cached details, with no new fetches for titles already resolved. It still does today's full reload when `invalidations` changed.
- **Home screen:**
  - OK on a card calls `vm.activate`: Play starts `PlayerActivity`; Open does what `onOpen` does today.
  - Long-press OK (`Surface(onLongClick = …)`) on a recent card always opens the title page.
  - Only the recent row's cards get `onLongClick`.
- **Double-press guard:** an `activate` that returns Play while a previous Play was not followed by `onResume()` returns nothing. Model `activate` as returning `HomeAction?`. This is the same double-press guard as the title page's `startPending`.

- [ ] **Step 1: Write the failing tests** in `HomeViewModelTest`, extending the fakes with `FakeWatchStore` and `FakeTitlePrefs`.
  - **`recentRow_showsPendingThenResolvedLabel`:**
    - Input: server recent [A]; A's detail has S1E1 played.
    - Expect: once idle, the row's card subtitle is «فصل ۱ · قسمت ۲».
  - **`recentRow_dropsFullyWatchedSeries`.**
  - **`recentRow_appendsLocalOnlyTitle`:**
    - Input: a local entry for a title not on the server list; its detail resolves.
    - Expect: the card is appended.
  - **`recentRow_failedDetail_keepsPendingCard`:**
    - Input: the A fetch throws, B resolves.
    - Expect: both cards are present; A's subtitle is null.
  - **`activate_resolvedRecentCard_playsNextEpisode`:**
    - Expect: `Play(PlayerArgs(A, "s1e2", dub = false))`.
  - **`activate_pendingCard_opensTitle`.**
  - **`activate_twiceWithoutResume_secondIsNull`.**
  - **`onResume_refreshesProgressFromStore_withoutRefetch`:**
    - Steps: after the first resolve, add a local entry and call `onResume()` with invalidations unchanged.
    - Expect: the card shows progress; the fake's `title()` call count is unchanged.
  - **`activate_otherRow_opensTitle`.**
- [ ] **Step 2: Run them; they should fail.** Run `--tests '*HomeViewModelTest'`. Expected: the new tests FAIL.
- [ ] **Step 3: Implement** the view model, then wire the factory (`graph.watchStore`, `graph.titlePrefs`), `LandscapeCard` (`onLongClick` and `progress` from `CardUi`), `HomeScreen` (calls `activate`) and `DroozNavHost` (Play → `PlayerActivity.intent`).
- [ ] **Step 4: Run them; they should pass.** Run the full suite and `:app:assembleDebug`. Expected: PASS and a successful build.
- [ ] **Step 5: Commit** with `feat(home): Continue Watching resolves the next episode and plays it on OK`.

---

### Task 5: «لیست من» screen

**Files:**
- Modify:
  - `ui/DroozNavHost.kt` (`RailItem.MyList`)
  - `ui/list/ListViewModel.kt` / `ListScreen.kt` (empty-state copy)
  - `res/values/strings.xml`
- Test: `app/src/test/java/com/alikarami/drooz/ui/list/ListViewModelTest.kt`

**Interfaces:**
- **Consumes:** `ListRoute(title, path)`, `ListViewModel`, `SiteApi.list`.
- **Produces:**
  - `ListRoute` gains `emptyText: String? = null`.
  - `ListUiState` exposes `empty: Boolean`: true once loaded with zero items and no error.

**Decisions:**
- `RailItem.MyList` → `go(entry, ListRoute(getString(R.string.title_my_list), "/user/watchlist/", getString(R.string.my_list_empty)))`.
- The new string is `my_list_empty` = «لیست شما خالی است».
- `ListScreen` shows `emptyText` (or the existing generic empty copy, if any) centred when `empty`.
- The list is reloaded each time the screen is entered. It is a new back-stack entry, so this is already true; do not cache.

- [ ] **Step 1: Write the failing tests:**
  - **`emptyPage_reportsEmpty`:** `list()` returns `ListPage(emptyList(), 1, 1)` → `empty == true`.
  - **`errorPage_isNotEmpty`:** `list()` throws → `empty == false`, error set.
- [ ] **Step 2: Run them; they should fail.** Expected: FAIL (no `empty`).
- [ ] **Step 3: Implement** `empty`, the route parameter, the rail wiring and the copy.
- [ ] **Step 4: Run them; they should pass.** Run the full suite and `assembleDebug`. Expected: PASS.
- [ ] **Step 5: Commit** with `feat(list): «لیست من» from the site watchlist`.

---

### Task 6: Settings data layer

**Files:**
- Create:
  - `app/src/main/java/com/alikarami/drooz/ui/settings/SettingsViewModel.kt`
  - `app/src/main/java/com/alikarami/drooz/data/CacheCleaner.kt`
- Modify:
  - `data/SiteApi.kt` (`AuthApi`)
  - `AppGraph.kt`
  - `ui/AppViewModel.kt` (`onLoggedOut`)
- Test:
  - `app/src/test/java/com/alikarami/drooz/ui/settings/SettingsViewModelTest.kt`
  - `ui/AppViewModelTest.kt`

**Interfaces:**
- **Consumes:** `PlayerPrefs` and `PlayerSettings` (Plan 3); `TitlePrefs.defaultDub`/`setDefaultDub` (Task 2); `AuthService.logout()`; `CredentialStore.load()`.
- **Produces:**
  ```kotlin
  // AuthApi additions (AuthApiAdapter implements them; existing fakes get trivial overrides)
  val username: String?   // from the stored credentials; null if none
  suspend fun logout()

  interface CacheCleaner { suspend fun clear() }
  /** Image disk + memory cache, home page cache, title cache. */
  class AppCacheCleaner(imageLoader: ImageLoader, homeCache: HomePageCache, titleInfo: TitleInfoRepository) : CacheCleaner

  data class SettingsUiState(
      val player: PlayerSettings,
      val defaultDub: Boolean,
      val username: String?,
      val version: String,
      val cacheCleared: Boolean,
      val confirmLogout: Boolean,
      val busy: Boolean,
  )
  sealed interface SettingsEvent { data object LoggedOut : SettingsEvent }
  class SettingsViewModel(
      playerPrefs: PlayerPrefs,
      titlePrefs: TitlePrefs,
      auth: AuthApi,
      cache: CacheCleaner,
      version: String,
  ) : ViewModel() {
      val uiState: StateFlow<SettingsUiState>
      val events: Flow<SettingsEvent>
      fun setSubtitle(choice: SubtitleChoice)        // FA, EN, DUAL, OFF only; FA_DUB ignored
      fun stepSize(delta: Int)                       // ±1 step within SUBTITLE_SIZES
      fun setColor(c: SubtitleColor)
      fun setBackground(opacity: Int)                // one of BACKGROUND_OPACITIES
      fun setAudioBoost(on: Boolean)
      fun setDefaultDub(on: Boolean)
      fun clearCache()
      fun requestLogout()                            // shows the confirmation
      fun cancelLogout()
      fun confirmLogout()                            // auth.logout(), then emits LoggedOut
  }
  ```
- **`AppViewModel.onLoggedOut()`** calls `forgetSession()` and sets `loginPending = false`, so a debug play never waits on it.

**Decisions:**
- Settings writes go through `PlayerPrefs.update`, so the player (which already collects `settings`) picks them up.
- `confirmLogout` emits `LoggedOut` even when the site's logout request fails. `AuthService.logout` already swallows the network error and clears the local session.
- **Version** comes from `BuildConfig.VERSION_NAME`, shown as «نسخه 0.1.0». The version stays Latin, as the site shows versions.

- [ ] **Step 1: Write the failing tests:**
  - **`sizeStep_clampsToRange`:** at 200, `stepSize(+1)` → still 200; at 60, `stepSize(-1)` → still 60.
  - **`subtitleChoice_faDubIgnored`.**
  - **`audioBoost_persists`:** the fake prefs show `audioBoost == false` after `setAudioBoost(false)`.
  - **`defaultDub_persists`.**
  - **`clearCache_callsCleaner_andFlags`.**
  - **`logout_needsConfirmation_thenEmitsLoggedOut`:**
    - Steps: call `requestLogout()`.
    - Expect: `confirmLogout == true` and `auth.logout` not yet called.
    - Then: `confirmLogout()` → `auth.logout` called once; `LoggedOut` emitted.
  - **`logout_failure_stillLogsOut`:** the fake's `logout` throws an IOException → `LoggedOut` is still emitted.
  - **`AppViewModelTest.onLoggedOut_clearsCaches`.**
- [ ] **Step 2: Run them; they should fail.** Expected: FAIL (unresolved).
- [ ] **Step 3: Implement.** Update every existing `AuthApi` fake (`AppViewModelTest`, `LoginViewModelTest`) with `username = null` and an empty `logout()`. In `AppGraph`, add `val cacheCleaner: CacheCleaner = AppCacheCleaner(imageLoader, homeCache, titleInfo)`.
- [ ] **Step 4: Run them; they should pass.** Run the full suite. Expected: PASS.
- [ ] **Step 5: Commit** with `feat(settings): settings view model, cache cleaner, logout`.

---

### Task 7: Settings screen

**Files:**
- Create: `app/src/main/java/com/alikarami/drooz/ui/settings/SettingsScreen.kt`
- Modify:
  - `ui/Routes.kt` (`@Serializable data object Settings`)
  - `ui/DroozNavHost.kt` (`RailItem.Settings` route; `LoggedOut` → `appVm.onLoggedOut()` then `resetTo(Login)`)
  - `res/values/strings.xml`

**Interfaces:**
- **Consumes:** `SettingsViewModel` (Task 6), `SettingsViewModelFactory(graph)`.
- **Reuse from the player:** the player's subtitle preview and options rendering in `player/ui/Panels.kt` and `SubtitleLayer.kt`. Where a composable there is private, extract it to `ui/common` or make it internal. Do not duplicate it.

**Decisions:**
- **Layout:** a vertical list of sections, RTL, with the same dark theme and `droozFocus` styling as the rest of the app:
  1. **«زیرنویس»:**
     - language row (فارسی / English / فارسی + English / خاموش);
     - size stepper (− / value / +; holding repeats like the player's);
     - colour swatches;
     - background opacity options;
     - a live preview line «نمونه زیرنویس — Sample subtitle» styled with the current settings.
  2. **«صدا»:**
     - Audio Boost toggle («تقویت صدا»);
     - «زبان پیش‌فرض: دوبله فارسی (در صورت وجود)» toggle.
  3. **«حافظه»:** «پاک کردن حافظه موقت» button. After a press it shows «پاک شد» until the next change.
  4. **«حساب کاربری»:** the username (Latin, bidi-isolated) and «خروج از حساب». Pressing it opens a confirm dialog («از حساب خارج می‌شوید؟» with «خروج» / «انصراف»); the initial focus in the dialog is «انصراف».
  5. **«درباره»:** «نسخه 0.1.0».
- **Focus:**
  - Initial focus goes to the first control.
  - Back closes the dialog if open; otherwise it leaves Settings.
  - Every control is reachable with the D-pad.
- **Toggles:** reuse the title page's pill style (`PillButton`/`DubSwitch` look) for toggles and option chips, so they look like the rest of the app.

- [ ] **Step 1: Implement the screen, route and nav wiring.** There are no UI tests in this repo; the view-model behaviour is covered by Task 6.
- [ ] **Step 2: Build.** Run `./gradlew :app:assembleDebug` and the full suite. Expected: BUILD SUCCESSFUL and the suite PASS.
- [ ] **Step 3: Commit** with `feat(settings): settings screen`.

---

### Task 8: Player polish (deferred from Plan 3)

**Files:**
- Modify:
  - `player/PlaybackRules.kt` (`qualityOptions`)
  - `player/engine/ExoPlaybackEngine.kt`, `PlaybackEngine.kt`
  - `player/audio/AudioBoost.kt`
  - `player/PlayerViewModel.kt`
  - `player/ui/PlayerOverlay.kt`
- Test:
  - `player/PlaybackRulesTest.kt`
  - `player/audio/AudioBoostTest.kt`
  - `player/PlayerViewModelTest.kt`

**Interfaces:**
- **Changed:**
  - `fun qualityOptions(variants: List<VariantInfo>, siteLabels: Map<Int, String>): List<QualityOption>`, with `data class VariantInfo(val height: Int, val bitrateKbps: Int?)`.
  - The engine's track info carries each video variant's `Format.bitrate` (or `peakBitrate`) in kbps.

**Decisions:**
- **Quality labels:**
  - A site label is matched to a variant by bitrate key: exact first, then nearest within ±15 %.
  - Only when the bitrate is unknown or nothing matches does it fall back to today's leading-number height match, then to «{h}p».
  - Several variants of the same height keep the highest-bitrate one.
- **`GainAudioProcessor`:**
  - Override `onReset()` to reset the applied gain to 1.0.
  - The engine's `release()` becomes idempotent: a second call is a no-op.
- **Stream errors:** an `Error` event from the old stream (`oldStreamOnEngine`) that arrives after the new stream's load has started is ignored. It must not show Failed.
- **Up next:** if a panel is open when up-next becomes due, the up-next card waits until the panel closes instead of force-closing it. The countdown starts when the card shows.
- **Held FF/REW** while a panel is open is ignored.

- [ ] **Step 1: Write the failing tests:**
  - **`qualityOptions_matchesByBitrateKey`:** variants (1080, 2475 kbps) and (1080, 1200 kbps), plus labels {2475: "1080 [ 2.48 GB ]", 1200: "1080 [ 1.2 GB ]"} → one 1080 option labelled "1080 [ 2.48 GB ]".
  - **`qualityOptions_nearestWithin15Percent`:** variant 2400 matches key 2475.
  - **`qualityOptions_unknownBitrate_fallsBackToHeight`.**
  - **`gainProcessor_resetRestoresUnityGain`.**
  - **`oldStreamErrorAfterSwitch_isIgnored`:** with the FakeEngine, after `playNext` starts the new load, emit `Error` tagged with the old stream → state is not Failed.
  - **`upNextDue_withPanelOpen_waitsForClose`:** open a panel, reach up-next → no up-next shown; close the panel → up-next shows with the full 8 s countdown.
- [ ] **Step 2: Run them; they should fail.** Expected: the new tests FAIL.
- [ ] **Step 3: Implement.** Adapt existing `qualityOptions` call sites and tests to `VariantInfo`.
- [ ] **Step 4: Run them; they should pass.** Run the full suite and `assembleDebug`. Expected: PASS.
- [ ] **Step 5: Commit** with `fix(player): bitrate-matched quality labels, gain reset, up-next waits for panels`.
