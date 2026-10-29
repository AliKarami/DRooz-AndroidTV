# Plan 3 — Player Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the minimal `PlayerActivity` with a TV player that has everything the site's web player has (spec §6): quality list with sizes + Automatic, audio tracks incl. 5.1, Farsi / English / English+Farsi / Farsi-for-Dub subtitles with a remembered choice, subtitle look and sync, subtitles in the letterbox bars, Audio Boost, resume, auto-next, speed, ±10 s with accelerating hold-scrub, keyframe-snapped seeks and error reporting.

**Architecture:** The player opens the stream itself (title page only hands it `PlayerArgs`), so auto-next, the episode strip and error recovery can reopen stream pages without going back. All decisions live in pure, JVM-tested Kotlin (`PlaybackRules`, `Srt`, `AudioBoost`, `SubtitlePlacement`); `PlayerViewModel` orchestrates them against a `PlaybackEngine` interface (Media3 behind it, a fake in tests). Subtitles are downloaded and rendered by us (not by ExoPlayer's text renderer) so dual subtitles, sync offset, retries, Vazirmatn styling and letterbox placement are fully ours. Local watch state goes in a small JSON-file `WatchStore` that Plan 4 builds Continue Watching on.

**Tech Stack:** Kotlin 2.4.21, Media3 1.11.1 (ExoPlayer, HLS, OkHttp data source), Compose + tv-material 1.1.0, DataStore 1.3.0, kotlinx-serialization 1.12.0, OkHttp 5.5.1 (+ mockwebserver3), JUnit4 + kotlinx-coroutines-test + Turbine.

**Spec:** `docs/superpowers/specs/2026-10-28-dlrooz-tv-design.md` (§4.3 stream page, §6 player table + controls overlay, §7.1 local watch state). Reference behaviour: the site's own player script in `app/src/test/resources/fixtures/stream_film_ouatime.html` (lines ~80–460).

## Global Constraints

- Build: `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew :app:testDebugUnitTest` (JVM tests) and `:app:assembleDebug`. Never add a dependency not already in `gradle/libs.versions.toml` (Media3 modules `exoplayer`, `exoplayer-hls`, `ui`, `datasource-okhttp` are there; nothing else needed).
- Public repo: never commit credentials, cookies, the user token, user id, phone number or m3u8 keys. No sensitive logging in release builds: stream / CDN / subtitle URLs carry the user's token — log only under `BuildConfig.DEBUG`.
- Never open a `/stream/` page except as the direct result of the user asking to play (opening it marks the title streamed on the account). No prefetching of stream pages or segments.
- Persian UI copy: static strings in `res/values/strings.xml`; copy built in Kotlin (with numbers) in `UiStrings` using `toPersianDigits()`. Titles stay in their original language.
- App layout is RTL. **Exception (spec §6 #12, designer's call): the seek bar and the −10 / play / +10 cluster are laid out LTR — physical right = forward, as on every TV app.**
- `safeLaunch` (ui/common/States.kt) rethrows `CancellationException`; keep that contract in any new coroutine helper.
- Commits end with:
  ```
  Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
  Claude-Session: https://claude.ai/code/session_015MGi3LciBpjuWunganGRWV
  ```
- Agents never press Play/OK on the TV, never log in/out, never clear app data. Device work in this plan is limited to `adb install` (Task 12).

### Designer's calls taken in this plan (rulings against the spec)

1. **WatchStore is a JSON file, not Room** (spec §8 says Room). Reason: JVM-testable without Robolectric, no new KSP/Room dependency, a few hundred rows at most. Plan 4 reads it through the `WatchStore` interface, so swapping later costs one class.
2. **The player opens the stream page itself**; the title page no longer waits on `/stream/` (its busy spinner goes away; the player shows a backdrop + spinner loading screen instead, and Back there cancels the HTTP call — the deferred "cancel `/stream/` on Back" item).
3. **Dual subtitles = the separate Farsi and English SRTs** rendered in two text views (Persian in the picture, English in the bar), not the site's merged `&second=` file. Spec §9 left this open.
4. **Defaults follow this account's site settings** found in the fixture: subtitle colour yellow, background opacity 75 %, Audio Boost on, Farsi subtitles.
5. **Up-next timing:** card appears when ≤ 40 s remain (episodes ≥ 2 min long), countdown 8 s (mockup copy «پخش خودکار تا ۸ ثانیه دیگر»).
6. **Stream page height stays `h=1080`** (ABR picks the real quality; "Automatic" is the default).

## Review Focus

1. **Stream key expiry mid-playback** (paused for an hour, then resume → CDN 403/410): expected silent recovery — reopen the stream page at the current position, at most once per 60 s; only a second failure shows the error screen. Test: `PlayerViewModelTest.httpErrorDuringPlaybackReopensStreamOnce` (Task 7).
2. **Subtitle download fails or returns junk** (site drops 25–60 % of requests; a login page instead of SRT): playback must start and continue; the failed language is simply absent from the cues, never a crash. Tests: `SrtParserTest.htmlInsteadOfSrtGivesNoCues` (Task 2), `PlayerViewModelTest.subtitleFailureDoesNotBlockPlayback` (Task 8).
3. **Back pressed while the stream page is loading**: the OkHttp call is cancelled and the player never starts afterwards. Tests: `SiteHttpTest.cancellingTheCoroutineCancelsTheCall` (Task 1), `PlayerViewModelTest.closeWhileOpeningNeverLoadsEngine` (Task 7).
4. **Last episode of a season / of the series**: next rolls into the next season's first episode; after the very last one there is no card and Ended exits. Tests: `PlaybackRulesTest.nextEpisodeCrossesSeasons`, `nextEpisodeAfterLastIsNull` (Task 3), `PlayerViewModelTest.endedOnLastEpisodeExits` (Task 9).
5. **Seeks near the edges** (−10 s at 0:04, +10 s in the last seconds, keyframe list empty): never negative, never past duration, never stuck on the same keyframe. Tests: `PlaybackRulesTest.snapClampsAtStartAndEnd`, `snapWithoutKeyframesReturnsTarget`, `snapForwardNeverStaysPut` (Task 3).

---

## File Structure

| File | Responsibility |
|---|---|
| `site/http/SiteHttp.kt` (modify) | Calls become cancellable (coroutine cancel → `Call.cancel()`). |
| `site/SiteRepository.kt`, `data/SiteApi.kt`, `data/SessionGuard.kt` (modify) | `subtitle(url)`, `reportStreamError(...)` on `SiteApi`. |
| `site/model/Models.kt` (modify) | `SubtitleKind` + `SubtitleTrack.kind`. |
| `player/PlayerArgs.kt` | What the title page hands the player (+ Intent round-trip). |
| `player/subtitle/Srt.kt` | `SubCue`, `SrtParser`, `SubtitleTimeline`. |
| `player/PlaybackRules.kt` | Keyframe snap, resume, finished, up-next, next episode, scrub step, quality options. |
| `player/audio/AudioBoost.kt` | `GainSchedule`, `GainSmoother`, `GainAudioProcessor`, `AudioOption`, `pickAudioTrack`. |
| `data/PlayerPrefs.kt` | Global player settings (DataStore). |
| `data/WatchStore.kt` | Local per-stream progress + sync offset (JSON file). |
| `player/engine/PlaybackEngine.kt` | Engine interface + `ExoPlaybackEngine` (Media3). |
| `player/PlayerViewModel.kt` | Orchestration and UI state. |
| `player/ui/SubtitleLayer.kt` | `subtitlePlacement()` + subtitle composables. |
| `player/ui/Controls.kt` | Overlay, seek bar, key handling. |
| `player/ui/Panels.kt` | Subtitle & audio panel, quality, speed, episodes strip, up-next card. |
| `player/PlayerActivity.kt` (rewrite) | Compose host, engine lifetime. |
| `ui/title/*`, `ui/DroozNavHost.kt` (modify) | Play hands `PlayerArgs` to the player. |

All paths below are under `app/src/main/java/com/alikarami/drooz/` (main) and `app/src/test/java/com/alikarami/drooz/` (test) unless absolute.

---

### Task 1: Cancellable site calls, subtitle download, error reporting on `SiteApi`

**Files:**
- Modify: `site/http/SiteHttp.kt`, `site/SiteRepository.kt`, `data/SiteApi.kt`, `data/SessionGuard.kt`, `site/model/Models.kt`, `site/parse/StreamPageParser.kt` (only if needed for the kind)
- Modify (fakes): every test `FakeApi : SiteApi` (AppViewModelTest, SearchViewModelTest, HomeViewModelTest, TitleViewModelTest, ListViewModelTest, SessionGuardTest, TitleInfoRepositoryTest) — add `override suspend fun subtitle(url: String) = error("unused")` and `override suspend fun reportStreamError(streamId: String, event: String, msg: String) {}`
- Test: `site/http/SiteHttpTest.kt`, `site/SiteRepositoryTest.kt`, `site/parse/StreamPageParserTest.kt`

**Interfaces:**
- Produces:
  - `SiteApi.subtitle(url: String): String` — GET with the normal retry policy (3 attempts), requires auth; `url` is absolute (`SiteConfig.BASE_URL + path`) as the parser produces it.
  - `SiteApi.reportStreamError(streamId: String, event: String, msg: String)` — delegates to the existing `SiteRepository.reportStreamError` (already never throws, no retry).
  - `enum class SubtitleKind { FA, EN, DUAL, FA_DUB, OTHER }` with `companion fun of(label: String): SubtitleKind` — `"Farsi"`→FA, `"English"`→EN, `"English + Farsi"`→DUAL, `"Farsi for Dub"`→FA_DUB, else OTHER (exact, trimmed match).
  - `SubtitleTrack.kind: SubtitleKind` as a computed property (`get() = SubtitleKind.of(label)`), constructor unchanged.

- [ ] **Step 1: Write the failing tests**

```kotlin
// SiteHttpTest
@Test fun cancellingTheCoroutineCancelsTheCall() = runBlocking {
    server.enqueue(MockResponse.Builder().body("x").headersDelay(5, TimeUnit.SECONDS).build())
    val job = launch(Dispatchers.Default) { http.get("/slow/") }
    delay(300)
    val t0 = System.nanoTime()
    job.cancelAndJoin()
    assertTrue((System.nanoTime() - t0) < 1_000_000_000)          // returned promptly
    assertTrue(okHttpClient.dispatcher.runningCallsCount() == 0)   // the OkHttp call itself is gone
}

// SiteRepositoryTest
@Test fun subtitleFetchesAbsoluteUrlThroughTheSiteClient() // enqueue "1\n00:00:01,000 --> 00:00:02,000\nHi\n"; repo.subtitle(server.url("/json/subtitle/1/2/sub.srt?stream=3").toString()) returns that body; recorded request path == "/json/subtitle/1/2/sub.srt?stream=3"
@Test fun subtitleRetriesDroppedRequests()          // first response SocketPolicy disconnect, second 200 → returns body, 2 requests

// StreamPageParserTest
@Test fun subtitleKindsFromFixture() {
    val s = StreamPageParser.parse(Fixtures.load("stream_film_ouatime.html"), "371517")
    assertEquals(listOf(SubtitleKind.FA, SubtitleKind.EN, SubtitleKind.DUAL), s.subtitles.map { it.kind })
}
@Test fun farsiForDubKind() = assertEquals(SubtitleKind.FA_DUB, SubtitleKind.of("Farsi for Dub"))
```
Use the existing test client/server setup in each file (read it first); the `SiteHttp` test client must be the one whose `dispatcher` you inspect.

- [ ] **Step 2: Run them — expect FAIL** (`cancelling…` takes ~5 s / running call count 1; `subtitle` and `kind` don't compile).
  Run: `./gradlew :app:testDebugUnitTest --tests '*SiteHttpTest' --tests '*SiteRepositoryTest' --tests '*StreamPageParserTest'`

- [ ] **Step 3: Implement.** In `SiteHttp.execute`, replace the blocking `execute()` with `suspendCancellableCoroutine` + `call.enqueue(callback)` and `cont.invokeOnCancellation { call.cancel() }`; read the body on the callback thread (it's an OkHttp worker — fine), keep every existing check (status, login redirect, scr stub) and exception mapping (`IOException` → `NetworkError`, but an IOException after cancellation must not resume the continuation). Do the same for `getFinalUrl`. Add the two `SiteApi` methods to the interface, `SiteApiAdapter`, `SessionGuardedSiteApi` (guarded like the others) and `SiteRepository.subtitle(url)` (strip `SiteConfig.BASE_URL` / use `HttpUrl` so the request goes through `http.get(path)`), update the test fakes.

- [ ] **Step 4: Run the full suite — expect PASS** (`./gradlew :app:testDebugUnitTest`, all prior 233 + new).

- [ ] **Step 5: Commit** — `feat(site): cancellable calls, subtitle download, stream error reporting on SiteApi`

---

### Task 2: SRT parsing and the subtitle timeline

**Files:**
- Create: `player/subtitle/Srt.kt`
- Test: `player/subtitle/SrtParserTest.kt`, `player/subtitle/SubtitleTimelineTest.kt`

**Interfaces:**
- Produces:
  - `data class SubCue(val startMs: Long, val endMs: Long, val text: String)` — `text` lines joined with `\n`, tags removed.
  - `object SrtParser { fun parse(srt: String): List<SubCue> }` — sorted by `startMs`; never throws.
  - `class SubtitleTimeline(cues: List<SubCue>) { fun at(positionMs: Long): List<SubCue>; val isEmpty: Boolean }` — every cue with `startMs <= positionMs < endMs`, in start order (overlaps allowed). Binary search over start times; must be O(log n + k) per call (it runs every 100 ms).

- [ ] **Step 1: Write the failing tests**

```kotlin
@Test fun parsesBasicBlocks() {
    val cues = SrtParser.parse("1\n00:00:01,000 --> 00:00:02,500\nHello\nthere\n\n2\n00:01:00,000 --> 00:01:01,000\nBye\n")
    assertEquals(listOf(SubCue(1000, 2500, "Hello\nthere"), SubCue(60000, 61000, "Bye")), cues)
}
@Test fun handlesBomCrlfAndDotMillis()      // "﻿1\r\n00:00:01.000 --> 00:00:02.000\r\nA\r\n" → [SubCue(1000,2000,"A")]
@Test fun stripsFormattingTags()           // "<i>Hi</i> <font color=\"#fff\">you</font> {\\an8}x" → "Hi you x"
@Test fun skipsMalformedBlocksKeepsRest()  // a block with "00:00:xx --> bad" between two good ones → 2 cues
@Test fun missingIndexLinesStillParse()    // "00:00:01,000 --> 00:00:02,000\nA\n\n00:00:03,000 --> 00:00:04,000\nB" → 2 cues
@Test fun htmlInsteadOfSrtGivesNoCues()    // "<!DOCTYPE html><html>…login…</html>" → emptyList()
@Test fun persianTextKeptVerbatim()        // "من فقط می‌خواهم" (with ZWNJ) round-trips unchanged

// SubtitleTimelineTest
@Test fun findsActiveCue()                 // cues (1000..2000 "a"), (3000..4000 "b"): at(1500) == [a]; at(2000) == []; at(3999) == [b]
@Test fun overlappingCuesBothReturned()    // (1000..5000 a), (2000..3000 b): at(2500) == [a, b]
@Test fun beforeFirstAndAfterLastEmpty()   // at(0) == [], at(10_000) == []
@Test fun unsortedInputIsSorted()          // constructed from reversed list, same answers
```

- [ ] **Step 2: Run — expect FAIL** (`--tests '*SrtParserTest' --tests '*SubtitleTimelineTest'`: unresolved references).
- [ ] **Step 3: Implement** `Srt.kt`. Parse by scanning lines for the timing regex `(\d{1,2}):(\d{2}):(\d{2})[,.](\d{1,3})\s*-->\s*(\d{1,2}):(\d{2}):(\d{2})[,.](\d{1,3})`; text = following non-blank lines; tags `<[^>]+>` and `\{\\[^}]*\}` removed; blank-text cues dropped. Timeline: for long overlapping cues, scan backwards from the insertion point while `startMs > positionMs - MAX_CUE_MS` where `MAX_CUE_MS` = the longest cue duration in the list.
- [ ] **Step 4: Run — expect PASS.**
- [ ] **Step 5: Commit** — `feat(player): SRT parser and subtitle timeline`

---

### Task 3: Playback rules (pure)

**Files:**
- Create: `player/PlaybackRules.kt`
- Test: `player/PlaybackRulesTest.kt`

**Interfaces:**
- Consumes: `Season`, `Episode` (site/model).
- Produces (top-level in `com.alikarami.drooz.player`):
  - `fun snapToKeyframe(targetSec: Double, currentSec: Double, iframes: List<Double>, durationSec: Double): Double`
  - `const val RESUME_MIN_MS = 30_000L`, `const val FINISHED_FRACTION = 0.9`
  - `fun isFinished(positionMs: Long, durationMs: Long): Boolean` — `durationMs > 0 && positionMs >= durationMs * 0.9`
  - `fun resumePositionMs(savedMs: Long, durationMs: Long, finished: Boolean): Long` — `savedMs` if `savedMs > 30 s && !finished && !isFinished(savedMs, durationMs)`, else 0 (duration ≤ 0 = unknown: only the first two checks apply).
  - `const val UP_NEXT_LEAD_MS = 40_000L`, `const val UP_NEXT_COUNTDOWN_S = 8`, `const val UP_NEXT_MIN_DURATION_MS = 120_000L`
  - `fun upNextDue(positionMs: Long, durationMs: Long): Boolean` — `durationMs >= 120 s && durationMs - positionMs <= 40 s`
  - `fun nextEpisode(seasons: List<Season>, streamId: String): Episode?` — order by season number then episode number; the one after `streamId`; null when last or not found.
  - `fun scrubStepMs(repeatCount: Int): Long` — 0–4 → 10 000; 5–9 → 30 000; ≥10 → 60 000.
  - `data class QualityOption(val height: Int, val label: String)`; `fun qualityOptions(heights: List<Int>, siteLabels: Map<Int, String>): List<QualityOption>` — distinct heights, highest first; label = the site label whose leading integer equals the height (`"1080 [ 2.48 GB ]"` → 1080; keys of `siteLabels` are bitrates and are ignored), else `"${height}p"`.

`snapToKeyframe` ports the site's `seekControl` plugin (fixture lines ~180–215) with edge guards. Algorithm (the signature does not determine it):
```
if iframes.isEmpty() → return target.coerceIn(0.0, durationSec)
closest = index of iframe with minimal |target - iframe| (first wins on ties)
x = iframes[closest]
if x != target:
    seekGap = target - current
    isUpper = x > current; forward = seekGap > 0 && seekGap < 10; backward = seekGap < 0 && seekGap > -10
    theGap = |x - current|
    if forward && (!isUpper || theGap < 3) closest++
    else if backward && (isUpper || theGap < 3) closest--
    else if !forward && !backward && x > target closest--
    closest = closest.coerceIn(0, iframes.lastIndex)
return iframes[closest].coerceIn(0.0, durationSec)
```

- [ ] **Step 1: Write the failing tests** (`K = listOf(0.0, 6.5, 8.208, 18.167, 26.208, 27.375, 37.375, 43.0, 50.75, 60.75)` — the fixture's first keyframes, duration 8400.0)

```kotlin
@Test fun snapTenForwardLandsOnNearestKeyframe() = assertEquals(27.375, snapToKeyframe(30.0, 20.0, K, 8400.0), 0.0)
@Test fun snapSmallForwardKeepsFartherKeyframe() = assertEquals(43.0, snapToKeyframe(45.0, 40.0, K, 8400.0), 0.0)
@Test fun snapForwardNeverStaysPut() = assertEquals(50.75, snapToKeyframe(45.0, 42.0, K, 8400.0), 0.0)
@Test fun snapTenBackward() = assertEquals(18.167, snapToKeyframe(20.0, 30.0, K, 8400.0), 0.0)
@Test fun snapSmallBackwardMovesBack() = assertEquals(18.167, snapToKeyframe(25.0, 28.0, K, 8400.0), 0.0)
@Test fun snapExactKeyframeUnchanged() = assertEquals(37.375, snapToKeyframe(37.375, 10.0, K, 8400.0), 0.0)
@Test fun snapClampsAtStartAndEnd() {
    assertEquals(0.0, snapToKeyframe(-6.0, 4.0, K, 8400.0), 0.0)
    assertEquals(60.0, snapToKeyframe(70.0, 59.0, emptyList(), 60.0), 0.0)
}
@Test fun snapWithoutKeyframesReturnsTarget() = assertEquals(123.4, snapToKeyframe(123.4, 113.4, emptyList(), 8400.0), 0.0)

@Test fun resumeOnlyAfterThirtySecondsAndBeforeFinish() {
    assertEquals(0L, resumePositionMs(29_000, 3_600_000, false))
    assertEquals(31_000L, resumePositionMs(31_000, 3_600_000, false))
    assertEquals(0L, resumePositionMs(3_300_000, 3_600_000, false))   // ≥ 90 %
    assertEquals(0L, resumePositionMs(600_000, 3_600_000, true))      // marked finished
    assertEquals(600_000L, resumePositionMs(600_000, 0, false))       // unknown duration
}
@Test fun upNextWindow() {
    assertFalse(upNextDue(3_000_000, 3_600_000)); assertTrue(upNextDue(3_560_000, 3_600_000))
    assertFalse(upNextDue(70_000, 100_000))                            // too short to have credits
}
@Test fun nextEpisodeWithinSeason()     // s1: e1,e2 → next(e1) == e2
@Test fun nextEpisodeCrossesSeasons()   // s1: e1,e2; s2: e1 → next(s1e2) == s2e1, even when seasons/episodes are given out of order
@Test fun nextEpisodeAfterLastIsNull()  // next(s2e1) == null; unknown streamId → null
@Test fun scrubAccelerates() = assertEquals(listOf(10_000L, 10_000L, 30_000L, 60_000L), listOf(0, 4, 5, 10).map(::scrubStepMs))
@Test fun qualityOptionsUseSiteLabelsByHeight() = assertEquals(
    listOf(QualityOption(1080, "1080 [ 2.48 GB ]"), QualityOption(720, "720 [ 1.14 GB ]"), QualityOption(480, "480 [ 553 MB ]"), QualityOption(360, "360p")),
    qualityOptions(listOf(480, 1080, 720, 1080, 360), mapOf(2475 to "1080 [ 2.48 GB ]", 1100 to "720 [ 1.14 GB ]", 488 to "480 [ 553 MB ]")))
```

- [ ] **Step 2: Run — expect FAIL** (`--tests '*PlaybackRulesTest'`).
- [ ] **Step 3: Implement** `PlaybackRules.kt`.
- [ ] **Step 4: Run — expect PASS.**
- [ ] **Step 5: Commit** — `feat(player): playback rules (keyframe snap, resume, up-next, next episode, quality labels)`

---

### Task 4: Audio Boost and audio-track choice

**Files:**
- Create: `player/audio/AudioBoost.kt`
- Test: `player/audio/AudioBoostTest.kt`

**Interfaces:**
- Produces:
  - `class GainSchedule(gains: List<Pair<Double, Double>>, minGain: Double?, maxGain: Double?) { fun targetAt(positionSec: Double): Double }` — ports the site: walk segments accumulating `durationSec` (pair.first); the first segment whose cumulative end − 4 > position gives `clamp(gain × 0.9, min, max)`; past the end → `clamp(2.0, min, max)`; empty `gains` → 1.0. Null min/max = no bound on that side.
  - `class GainSmoother(initial: Double = 1.0) { val current: Double; fun step(target: Double): Double }` — first call always updates; afterwards updates only when `round(|target − current| × 100) > 1`; update = `round2((target + current × 17) / 18)`.
  - `const val GAIN_TICK_MS = 250L`
  - `class GainAudioProcessor : BaseAudioProcessor()` (`androidx.media3.common.audio.BaseAudioProcessor`) with `@Volatile var gain: Float = 1f`; handles `C.ENCODING_PCM_16BIT` and `C.ENCODING_PCM_FLOAT` (any channel count), throws `UnhandledAudioFormatException` otherwise; ramps linearly from the previously applied gain to `gain` across each buffer (no zipper noise); 16-bit output clamped to [−32768, 32767], float to [−1, 1].
  - `data class AudioOption(val label: String, val channels: Int)`; `fun pickAudioTrack(options: List<AudioOption>, dub: Boolean, multichannel: Boolean): Int?` — dub: first label containing `"dubbed"`; original: when `multichannel`, first label containing `"6CH"` and `"original"`, else first label containing `"original"` and not `"6CH"`; fallback 0; empty → null.

- [ ] **Step 1: Write the failing tests**

```kotlin
private val fixtureGains = listOf(10.0078 to 7.81, 10.0078 to 9.71, 9.98458 to 4.02, 10.0078 to 1.93)
@Test fun targetClampedToMax() = assertEquals(1.93, GainSchedule(fixtureGains, 0.64, 1.93).targetAt(0.0), 1e-9)
@Test fun targetUsesSegmentEndingMoreThanFourSecondsAhead() {
    val s = GainSchedule(fixtureGains, 0.64, 1.93)
    assertEquals(1.93, s.targetAt(7.0), 1e-9)          // 2nd segment (9.71×0.9 → clamp)
    assertEquals(1.737, s.targetAt(30.0), 1e-9)        // 4th segment: 1.93×0.9
}
@Test fun targetPastEndAndEmpty() {
    assertEquals(1.93, GainSchedule(fixtureGains, 0.64, 1.93).targetAt(1000.0), 1e-9)
    assertEquals(1.0, GainSchedule(emptyList(), null, null).targetAt(5.0), 1e-9)
}
@Test fun smootherApproachesLikeTheSite() {
    val g = GainSmoother()
    assertEquals(1.05, g.step(1.93), 1e-9)
    assertEquals(1.10, g.step(1.93), 1e-9)
}
@Test fun smootherIgnoresTinyDifferences() { val g = GainSmoother(); g.step(1.0); assertEquals(1.0, g.step(1.005), 1e-9) }

@Test fun processorScales16BitAndClips() // configure(AudioFormat(48000, 2, C.ENCODING_PCM_16BIT)); flush(); gain=2 applied twice (second buffer has no ramp): samples [1000, -1000, 30000, -30000] → [2000, -2000, 32767, -32768]
@Test fun processorRampsWithinABuffer()  // gain 1 → set 3; one 4-frame mono buffer of 1000s → strictly increasing outputs, last ≈ 3000, first ≥ 1000
@Test fun processorScalesFloat()        // PCM_FLOAT 0.25f × gain 2 → 0.5f; 0.8f × 2 → 1.0f
@Test fun processorRejectsOtherEncodings() // configure(AudioFormat(48000, 2, C.ENCODING_PCM_24BIT)) throws UnhandledAudioFormatException

@Test fun picksSixChannelOriginalOnlyWhenOutputSupportsIt() {
    val opts = listOf(AudioOption("original", 2), AudioOption("original-6CH", 6), AudioOption("dubbed-fa", 2))
    assertEquals(1, pickAudioTrack(opts, dub = false, multichannel = true))
    assertEquals(0, pickAudioTrack(opts, dub = false, multichannel = false))
    assertEquals(2, pickAudioTrack(opts, dub = true, multichannel = true))
}
@Test fun pickFallsBackToFirstAndNullWhenEmpty() // [AudioOption("und",2)] dub=true → 0; empty → null
```
Write buffers via `queueInput(ByteBuffer.allocateDirect(n).order(ByteOrder.nativeOrder())…)` then `getOutput()`.

- [ ] **Step 2: Run — expect FAIL** (`--tests '*AudioBoostTest'`).
- [ ] **Step 3: Implement** `AudioBoost.kt`.
- [ ] **Step 4: Run — expect PASS.**
- [ ] **Step 5: Commit** — `feat(player): audio boost (site gain map, smoothing, PCM gain processor) and audio-track choice`

---

### Task 5: Player settings and local watch state

**Files:**
- Create: `data/PlayerPrefs.kt`, `data/WatchStore.kt`
- Modify: `AppGraph.kt` (add `playerPrefs`, `watchStore`)
- Test: `data/PlayerPrefsTest.kt`, `data/WatchStoreTest.kt`

**Interfaces:**
- Produces:
  ```kotlin
  /** FA_DUB is per-playback only: it is never stored as the global choice. */
  enum class SubtitleChoice { FA, EN, DUAL, FA_DUB, OFF }
  enum class SubtitleColor(val argb: Long) { WHITE(0xFFFFFFFF), YELLOW(0xFFFFFF00), CYAN(0xFF7FE0FF), GREEN(0xFFA6F28C) }
  data class SubtitleStyle(val sizePercent: Int = 100, val color: SubtitleColor = SubtitleColor.YELLOW, val backgroundOpacity: Int = 75)
  data class PlayerSettings(val subtitle: SubtitleChoice = SubtitleChoice.FA, val style: SubtitleStyle = SubtitleStyle(), val audioBoost: Boolean = true)
  val SUBTITLE_SIZES = 60..200 step 10
  val BACKGROUND_OPACITIES = listOf(0, 25, 50, 75, 100)
  interface PlayerPrefs { val settings: Flow<PlayerSettings>; suspend fun update(transform: (PlayerSettings) -> PlayerSettings) }
  class DataStorePlayerPrefs(private val store: DataStore<Preferences>) : PlayerPrefs
  fun playerPrefsStore(context: Context): DataStore<Preferences>   // preferencesDataStore("player_prefs")

  @Serializable data class WatchEntry(
      val streamId: String, val refPath: String, val season: Int, val episode: Int,
      val positionMs: Long, val durationMs: Long, val updatedAt: Long, val finished: Boolean,
      val subtitleOffsetMs: Long = 0,
  )
  interface WatchStore {
      suspend fun get(streamId: String): WatchEntry?
      /** Inserts or replaces by streamId; stamps updatedAt from the store's clock. */
      suspend fun put(entry: WatchEntry)
      /** Newest first. */
      suspend fun all(): List<WatchEntry>
  }
  class FileWatchStore(private val file: File, private val clock: () -> Long = System::currentTimeMillis) : WatchStore
  const val WATCH_STORE_CAP = 500
  ```
- `FileWatchStore`: loads lazily once, `Mutex`-guarded, `Dispatchers.IO`; writes the whole list atomically (temp file + rename) on every `put`; a corrupt/unreadable file starts empty (no crash); beyond 500 entries the oldest `updatedAt` are dropped. Stored values outside their ranges (size, opacity) fall back to the default when read.

- [ ] **Step 1: Write the failing tests**

```kotlin
// PlayerPrefsTest — PreferenceDataStoreFactory.create(scope = backgroundScope, produceFile = { tmp.newFile("p.preferences_pb") })
@Test fun defaultsMatchTheAccountsSiteSettings() = runTest {
    assertEquals(PlayerSettings(SubtitleChoice.FA, SubtitleStyle(100, SubtitleColor.YELLOW, 75), true), prefs.settings.first())
}
@Test fun updateRoundTrips()          // update { it.copy(subtitle = DUAL, style = SubtitleStyle(130, CYAN, 25), audioBoost = false) } → settings.first() equals that
@Test fun faDubIsNeverPersisted()      // update to FA_DUB → stored choice stays the previous one (FA)
// WatchStoreTest — TemporaryFolder, clock = { now }
@Test fun putThenGetAndNewestFirst()  // put a at now=1, b at now=2 → all() == [b, a] with updatedAt 2, 1
@Test fun putReplacesSameStream()     // put a(pos 10) then a(pos 20) → all().size == 1, get(a).positionMs == 20
@Test fun survivesReopen()            // new FileWatchStore(sameFile).get(a) == stored a
@Test fun corruptFileStartsEmpty()    // file.writeText("{not json") → all() == emptyList(), put still works
@Test fun capsAtFiveHundred()         // 501 puts with increasing clock → all().size == 500 and the first is gone
```

- [ ] **Step 2: Run — expect FAIL** (`--tests '*PlayerPrefsTest' --tests '*WatchStoreTest'`).
- [ ] **Step 3: Implement**, then add to `AppGraph`: `val playerPrefs: PlayerPrefs = DataStorePlayerPrefs(playerPrefsStore(appContext))` and `val watchStore: WatchStore = FileWatchStore(appContext.filesDir.resolve("watch_state.json"))`.
- [ ] **Step 4: Run the full suite — expect PASS.**
- [ ] **Step 5: Commit** — `feat(data): player settings and local watch state`

---

### Task 6: `PlaybackEngine` and the Media3 implementation

**Files:**
- Create: `player/engine/PlaybackEngine.kt`, `player/engine/ExoPlaybackEngine.kt`
- Test: `player/engine/EngineMappingTest.kt` (pure helpers only)

**Interfaces:**
- Consumes: `GainAudioProcessor`, `AudioOption` (Task 4).
- Produces:
  ```kotlin
  data class EngineTracks(val heights: List<Int> = emptyList(), val playingHeight: Int? = null,
                          val audio: List<AudioOption> = emptyList(), val selectedAudio: Int? = null)
  sealed interface EngineEvent {
      data object Ready : EngineEvent          // first STATE_READY after each load()
      data object Ended : EngineEvent
      data class Error(val code: Int, val codeName: String, val httpStatus: Int?) : EngineEvent
  }
  data class VideoSize(val width: Int, val height: Int)
  interface PlaybackEngine {
      val tracks: StateFlow<EngineTracks>
      val events: Flow<EngineEvent>
      val isPlaying: StateFlow<Boolean>
      val buffering: StateFlow<Boolean>
      val videoSize: StateFlow<VideoSize?>
      val positionMs: Long
      val durationMs: Long            // 0 when unknown
      val bufferedMs: Long
      /** True when the current audio output takes ≥ 6 channels (HDMI → eARC soundbar). */
      val multichannelOutput: Boolean
      fun load(masterUrl: String, startMs: Long)
      fun play(); fun pause()
      fun seekTo(ms: Long)
      fun setSpeed(speed: Float)
      fun setGain(gain: Float)
      /** null = Automatic (ABR). */
      fun selectQuality(height: Int?)
      fun selectAudio(index: Int)
      fun release()
  }
  internal fun httpStatusOf(error: Throwable?): Int?       // walks causes for HttpDataSource.InvalidResponseCodeException.responseCode
  internal fun audioLabel(format: Format): String          // format.label ?: format.id ?: format.language ?: "und"
  ```
- `ExoPlaybackEngine(context: Context, client: OkHttpClient)`: `val exoPlayer: ExoPlayer` (public — only the activity uses it, for the surface). HLS through `OkHttpDataSource.Factory(client)`; a `DefaultRenderersFactory` subclass whose `buildAudioSink` is `DefaultAudioSink.Builder(context).setAudioProcessors(arrayOf(gainProcessor)).build()`; `setSeekParameters(SeekParameters.CLOSEST_SYNC)`; text track type disabled (`setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)` — we render subtitles ourselves); `selectQuality(h)` = `TrackSelectionOverride` on the video group restricted to tracks of height `h` (null clears video overrides); `selectAudio(i)` = override on the i-th audio track as listed in `tracks.audio`; `multichannelOutput` from `AudioCapabilities.getCapabilities(context, …).maxChannelCount >= 6`; `events` is a `MutableSharedFlow(extraBufferCapacity = 8)`; positions read on the main thread (the VM ticks on Main). Debug-only logging per Global Constraints.

- [ ] **Step 1: Write the failing tests**
```kotlin
@Test fun httpStatusFoundInCauseChain() // RuntimeException(PlaybackException("x", InvalidResponseCodeException(403, …), ERROR_CODE_IO_BAD_HTTP_STATUS)) → 403; IOException() → null; null → null
@Test fun audioLabelPrefersLabelThenIdThenLanguage() // Format.Builder().setLabel("original-6CH").setId("a") → "original-6CH"; setId("dubbed-fa") → "dubbed-fa"; setLanguage("en") → "en"; empty → "und"
```
- [ ] **Step 2: Run — expect FAIL** (`--tests '*EngineMappingTest'`).
- [ ] **Step 3: Implement** both files.
- [ ] **Step 4: Run the full suite + `./gradlew :app:assembleDebug` — expect PASS / BUILD SUCCESSFUL.**
- [ ] **Step 5: Commit** — `feat(player): PlaybackEngine interface and Media3 engine with gain processor`

---

### Task 7: `PlayerViewModel` — opening, resume, progress, recovery, error reporting

**Files:**
- Create: `player/PlayerArgs.kt`, `player/PlayerViewModel.kt`
- Modify: `ui/common/UiStrings.kt` (player copy)
- Test: `player/PlayerViewModelTest.kt`, `player/FakeEngine.kt` (test source set), `player/PlayerArgsTest.kt`

**Interfaces:**
- Consumes: `SiteApi.openStream/subtitle/reportStreamError` (Task 1), `TitleInfoRepository.get/invalidate/remember`, `TitlePrefs`, `PlaybackRules` (Task 3), `PlaybackEngine` (Task 6), `WatchStore`, `PlayerPrefs` (Task 5).
- Produces:
  ```kotlin
  data class PlayerArgs(val ref: TitleRef, val streamId: String, val dub: Boolean) {
      fun toIntent(intent: Intent): Intent          // extras "ref_path", "stream_id", "dub"
      companion object { fun from(intent: Intent): PlayerArgs? }   // null when extras are missing/invalid
  }
  sealed interface PlayerPhase {
      data object Loading : PlayerPhase
      data object Playing : PlayerPhase             // includes paused/buffering
      data class Failed(val message: String) : PlayerPhase
  }
  data class PlayerUiState(
      val phase: PlayerPhase = PlayerPhase.Loading,
      val title: String = "", val episodeLabel: String? = null, val backdropUrl: String? = null,
      // filled in by Tasks 8–9:
      val subtitle: SubtitleChoice = SubtitleChoice.FA, val availableSubtitles: List<SubtitleChoice> = emptyList(),
      val style: SubtitleStyle = SubtitleStyle(), val subtitleOffsetMs: Long = 0,
      val qualities: List<QualityOption> = emptyList(), val selectedQuality: Int? = null, val playingHeight: Int? = null,
      val audio: List<AudioOption> = emptyList(), val selectedAudio: Int? = null, val boost: Boolean = true, val speed: Float = 1f,
      val upNext: UpNext? = null, val seasonEpisodes: List<PlayerEpisode> = emptyList(), val currentStreamId: String = "", val hasNext: Boolean = false,
  )
  data class UpNext(val label: String, val secondsLeft: Int)
  class PlayerViewModel(
      args: PlayerArgs, private val engine: PlaybackEngine, private val api: SiteApi,
      private val titleInfo: TitleInfoRepository, private val titlePrefs: TitlePrefs,
      private val watch: WatchStore, private val prefs: PlayerPrefs,
      private val clock: () -> Long = System::currentTimeMillis,
  ) : ViewModel() {
      val state: StateFlow<PlayerUiState>
      val exits: Flow<Unit>                       // the activity finishes on each emission
      fun togglePlay()
      fun seekBy(deltaMs: Long)                   // snapped via snapToKeyframe with the session's iframes[0]
      fun seekTo(ms: Long)                        // snapped likewise (scrub commit)
      fun retry()
      fun onStop()                                // saves progress, pauses
      fun close()                                 // user Back: cancels a pending open, saves, emits exit
  }
  const val TICK_MS = 100L
  const val SAVE_EVERY_MS = 10_000L
  const val STREAM_HEIGHT = 1080
  const val REOPEN_MIN_INTERVAL_MS = 60_000L
  const val ERROR_REPORT_MIN_INTERVAL_MS = 60_000L
  ```
  (`PlayerEpisode` is a new `data class PlayerEpisode(val streamId: String, val label: String, val watched: Boolean)` in `PlayerViewModel.kt`.)
- Behaviour:
  - **Open** (init, and any later episode switch): phase Loading → `titleInfo.get(ref)` → episode by `streamId` from `catalog.dub` when `dub` else `catalog.main` (missing → Failed) → `api.openStream(ep, ref, if (dub) "dubbed-fa" else "original", STREAM_HEIGHT)` → `titleInfo.invalidate(ref)`; `session.backdropPath?.let { titleInfo.remember(ref, it) }` → start = `resumePositionMs(entry.positionMs, entry.durationMs, entry.finished)` from `watch.get(streamId)` → `engine.load(session.masterUrl, start)`; `engine.play()`. Phase Playing on `EngineEvent.Ready`. `title` = the card title; `episodeLabel` = `episodeLabel(season, number)` for series, null for films; `backdropUrl` from `BackdropResolver`/played backdrop (whatever the title page shows) before the session arrives, the session image after.
  - **Progress:** while playing, every `SAVE_EVERY_MS`, on pause/`onStop`/`close`/episode switch/Ended: `watch.put(WatchEntry(streamId, ref.path, season, number, pos, dur, 0, finished = isFinished(pos, dur) || upNextReached, subtitleOffsetMs))`.
  - **Recovery:** `EngineEvent.Error` after the first Ready with `httpStatus in {403, 404, 410}` or an `ERROR_CODE_IO_*` code, and no reopen in the last `REOPEN_MIN_INTERVAL_MS` → silently reopen the stream page and `load(newUrl, currentPosition)`; otherwise phase `Failed(UiStrings.PLAYBACK_FAILED)`. Open failures → `Failed(UiStrings.PLAY_FAILED_NETWORK)` for network/5xx, `Failed(UiStrings.PLAY_FAILED)` otherwise; `SiteException.NotLoggedIn` → emit exit (the app routes to Login). `retry()` reopens at the last saved/current position.
  - **Reporting:** each `EngineEvent.Error` → `api.reportStreamError(streamId, if (no Ready yet for this load) "setupError" else "error", code.toString())`, at most once per `ERROR_REPORT_MIN_INTERVAL_MS` (fire-and-forget, never affects phase).
  - **close():** cancels the open job (the HTTP call dies with it, Task 1), saves progress if anything played, emits exit. A stream that resolves after `close()` never reaches `engine.load`.
  - `onCleared()` releases the engine.

- [ ] **Step 1: Write the failing tests.** `FakeEngine : PlaybackEngine` records `loads: List<Pair<String, Long>>`, `seeks`, `played`, `paused`, `released`, `gains`, `qualitySelections`, `audioSelections`, `speeds`; has settable `positionMs`/`durationMs`/`multichannelOutput`, `MutableStateFlow` tracks, and `fun emit(e: EngineEvent)`. Fake `SiteApi` with programmable `openStream` (counts calls, can suspend on a `CompletableDeferred`), `subtitle`, recorded `reportStreamError` calls. Use `StandardTestDispatcher` + `Dispatchers.setMain`, a fixed clock you advance, and fixtures-built `TitleDetail` (series with s1e1, s1e2, s2e1; and a film).
```kotlin
@Test fun opensStreamAndLoadsEngineFromStart()          // no WatchEntry → loads(masterUrl, 0); openStream lang "original", height 1080; phase Playing after emit(Ready)
@Test fun dubArgsOpenDubbedCatalogAndLang()             // args.dub = true → openStream(ep from catalog.dub, lang "dubbed-fa")
@Test fun resumesFromSavedPosition()                    // WatchEntry(pos 600_000, dur 3_600_000, finished=false) → load(_, 600_000)
@Test fun finishedEntryStartsFromZero()
@Test fun openingInvalidatesTitleAndRemembersBackdrop() // titleInfo.lastKnown(ref) is stale (get refetches); playedBackdrops contains session.backdropPath
@Test fun savesProgressEveryTenSecondsWhilePlaying()    // isPlaying true, position 20_000 → advanceTimeBy(10_000) → watch.get(id).positionMs == 20_000
@Test fun onStopSavesAndPauses()
@Test fun httpErrorDuringPlaybackReopensStreamOnce()    // Ready, then Error(httpStatus 403) at pos 1_234_000 → openStream called twice, second load at 1_234_000, phase Playing; a second 403 within 60 s → phase Failed
@Test fun setupErrorReportedOncePerMinute()             // two Errors before Ready within 60 s → one report ("setupError", code); after advancing clock 60 s another Error → second report ("setupError" again as still no Ready)
@Test fun openFailureShowsNetworkMessageAndRetryWorks() // openStream throws NetworkError → Failed(PLAY_FAILED_NETWORK); retry() → second call succeeds → load
@Test fun notLoggedInExits()                            // openStream throws SiteException.NotLoggedIn → exits emits, no Failed phase
@Test fun closeWhileOpeningNeverLoadsEngine()           // openStream suspended; close(); complete the deferred → loads empty, exits emitted once
@Test fun seekByIsSnappedToKeyframes()                  // session.iframes[0] = K (Task 3), position 20_000 → seekBy(10_000) → seeks == [27_375]
// PlayerArgsTest
@Test fun intentRoundTrip()                             // PlayerArgs(ref, "371517", true).toIntent(Intent()) → from(...) == same  (Intent works in JVM tests only with returnDefaultValues — if not, test via a Bundle-free helper: `toExtras(): Map<String, Any>` / `fromExtras(get: (String) -> Any?)`, and have toIntent/from delegate to them)
@Test fun missingExtrasGiveNull()
```
- [ ] **Step 2: Run — expect FAIL** (`--tests '*PlayerViewModelTest' --tests '*PlayerArgsTest'`).
- [ ] **Step 3: Implement.** Copy: `UiStrings.PLAYBACK_FAILED = "پخش با مشکل روبه‌رو شد"`; reuse `PLAY_FAILED`, `PLAY_FAILED_NETWORK`.
- [ ] **Step 4: Run the full suite — expect PASS.**
- [ ] **Step 5: Commit** — `feat(player): player view model - open, resume, progress, recovery, error reporting`

---

### Task 8: `PlayerViewModel` — subtitles, audio, quality, speed, boost

**Files:**
- Modify: `player/PlayerViewModel.kt`
- Test: `player/PlayerViewModelTest.kt` (extend)

**Interfaces:**
- Consumes: `SrtParser`, `SubtitleTimeline` (Task 2), `GainSchedule`, `GainSmoother`, `GAIN_TICK_MS`, `pickAudioTrack` (Task 4), `qualityOptions` (Task 3), `SubtitleKind` (Task 1).
- Produces (on `PlayerViewModel`):
  ```kotlin
  data class SubtitleCues(val primary: String? = null, val secondary: String? = null)   // DUAL: primary = Farsi, secondary = English
  val cues: StateFlow<SubtitleCues>
  fun setSubtitle(choice: SubtitleChoice)
  fun shiftSubtitles(deltaMs: Long)            // ±500 per press; persisted per stream in WatchEntry.subtitleOffsetMs
  fun setStyle(style: SubtitleStyle)
  fun setQuality(height: Int?)                 // null = Automatic
  fun setAudio(index: Int)
  fun setBoost(on: Boolean)
  fun setSpeed(speed: Float)
  val SPEEDS = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)
  const val SUBTITLE_STEP_MS = 500L
  ```
- Behaviour:
  - `availableSubtitles` = choices backed by the session's tracks: FA (kind FA), EN (EN), DUAL (both FA and EN present), FA_DUB (FA_DUB), plus OFF always; order FA, EN, DUAL, FA_DUB, OFF.
  - Initial choice: dub playback → FA_DUB if available else OFF; original → the stored global choice if available, else FA, else EN, else OFF. `setSubtitle` stores the global choice (except FA_DUB, which PlayerPrefs ignores).
  - Downloads only what the choice needs (DUAL = FA + EN files, never the merged track), once per session, in parallel with playback start; a failed/empty download leaves that side blank (no error UI, no blocking). Cache per stream id.
  - Every `TICK_MS`: `cues` = timeline.at(position − offset) text joined with `\n` (multiple active cues), per side.
  - Audio: on the first non-empty `tracks.audio` after each load → `engine.selectAudio(pickAudioTrack(audio, dub, engine.multichannelOutput))`. `setAudio(i)`: selects; if the chosen label contains `"dubbed"` and FA_DUB exists → FA_DUB, else if switching back from a dubbed track → the global choice; persists `titlePrefs.setDubSelected(ref.postId, isDubbed)`.
  - Quality: `qualities = qualityOptions(tracks.heights, session.qualityLabels)`; `playingHeight` mirrors `tracks.playingHeight`; Automatic is the default and is not persisted.
  - Boost: when `settings.audioBoost`, every `GAIN_TICK_MS` while playing → `engine.setGain(smoother.step(schedule.targetAt(positionSec)).toFloat())`; when off → `setGain(1f)` once. Toggle persists to PlayerPrefs. A seek resets nothing (the site doesn't either).
  - Speed: `engine.setSpeed`; per playback only (not persisted); kept across episode switches within the player.

- [ ] **Step 1: Write the failing tests**
```kotlin
@Test fun defaultFarsiSubtitleShownAtPosition()          // FA srt "1\n00:00:01,000 --> 00:00:03,000\nسلام\n"; position 2_000 → advanceTimeBy(TICK_MS) → cues.primary == "سلام"
@Test fun dualShowsFarsiAndEnglishFromSeparateFiles()    // setSubtitle(DUAL) → api.subtitle called for the FA and EN urls (not the "&second=" one); cues == SubtitleCues("سلام", "Hello")
@Test fun syncOffsetShiftsAndPersistsPerEpisode()        // shiftSubtitles(+500) twice → at position 3_500 the 1–3 s cue is still shown; watch.get(id).subtitleOffsetMs == 1000 after onStop(); a new VM for the same stream starts with offset 1000
@Test fun subtitleFailureDoesNotBlockPlayback()          // api.subtitle throws NetworkError → engine still loaded, phase Playing, cues empty, no Failed
@Test fun choiceRememberedGloballyButNotFaDub()          // setSubtitle(EN) → prefs.settings.first().subtitle == EN; setSubtitle(FA_DUB) → still EN
@Test fun dubPlaybackSelectsFarsiForDubOrOff()           // dub args + session with FA_DUB track → subtitle == FA_DUB; without → OFF
@Test fun autoPicksSixChannelWhenOutputSupportsIt()     // engine.multichannelOutput = true; tracks.audio = [original, original-6CH, dubbed-fa] → audioSelections == [1]
@Test fun switchingToDubbedAudioPicksDubSubtitleAndPersistsDub() // setAudio(2) → subtitle FA_DUB (if available), titlePrefs.dubSelected(postId) == true
@Test fun qualityMenuUsesSiteLabels()                    // tracks.heights [1080,720,480], session.qualityLabels fixture map → qualities labels "1080 [ 2.48 GB ]"…; setQuality(720) → engine.qualitySelections == [720]; setQuality(null) → [720, null]
@Test fun boostFollowsGainMapWhilePlaying()              // session.gains fixture, min .64 max 1.93, position 0, playing → advanceTimeBy(250) → engine.gains.last() == 1.05f
@Test fun boostOffSetsUnityGainAndPersists()             // setBoost(false) → engine.gains.last() == 1f; prefs.settings.first().audioBoost == false
@Test fun speedAppliesToEngine()                         // setSpeed(1.5f) → speeds == [1.5f], state.speed == 1.5f
```
- [ ] **Step 2: Run — expect FAIL.**
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run the full suite — expect PASS.**
- [ ] **Step 5: Commit** — `feat(player): subtitles (choice, dual, sync), audio tracks incl. 5.1, quality, speed, audio boost`

---

### Task 9: `PlayerViewModel` — up next, episode switching

**Files:**
- Modify: `player/PlayerViewModel.kt`, `ui/common/UiStrings.kt`
- Test: `player/PlayerViewModelTest.kt` (extend)

**Interfaces:**
- Consumes: `nextEpisode`, `upNextDue`, `UP_NEXT_COUNTDOWN_S` (Task 3).
- Produces (on `PlayerViewModel`): `fun playNext()`, `fun playEpisode(streamId: String)`, `fun dismissUpNext()`; state fields `upNext`, `seasonEpisodes` (current season, `watched` = server `played` || local `WatchEntry.finished`), `currentStreamId`, `hasNext`. `UiStrings.upNextLabel(season: Int, episode: Int): String = "قسمت بعدی · فصل $season قسمت $episode"` (Persian digits).
- Behaviour:
  - Each tick: if `hasNext` and `upNextDue(pos, dur)` and not dismissed for this episode → `upNext = UpNext(label, secondsLeft)`, counting down from `UP_NEXT_COUNTDOWN_S` once per second of wall time while playing; at 0 → `playNext()`. Seeking back out of the window hides the card and resets the countdown (not dismissed). Reaching the window counts as finished for the save.
  - `dismissUpNext()` hides it for this episode ("watch credits").
  - `EngineEvent.Ended` → `playNext()` if there is a next, else save as finished and emit exit.
  - `playNext()` / `playEpisode(id)`: save current progress, then the Open flow of Task 7 for the new stream id (same dub flag, same speed, re-derived subtitles), `upNext = null`, `dismissed = false`.

- [ ] **Step 1: Write the failing tests**
```kotlin
@Test fun upNextAppearsInLastFortySecondsAndCountsDown()   // series s1e1 (next s1e2), dur 3_600_000, pos 3_565_000, playing → upNext.label == "قسمت بعدی · فصل ۱ قسمت ۲", secondsLeft 8; advanceTimeBy(3_000) → 5
@Test fun countdownZeroPlaysNext()                         // → openStream called with s1e2's streamId; currentStreamId == s1e2; previous entry saved finished = true
@Test fun dismissKeepsWatchingCredits()                    // dismissUpNext() → upNext null and stays null on later ticks
@Test fun seekingBackHidesCardWithoutDismissing()          // card shown, position → 1_000_000 → upNext null; position back to 3_570_000 → shown again from 8
@Test fun endedPlaysNextAcrossSeasons()                    // current s1e2 → Ended → opens s2e1
@Test fun endedOnLastEpisodeExits()                        // current s2e1 → Ended → exits emits; no openStream; hasNext false; no card ever
@Test fun filmHasNoUpNext()                                // film near end → upNext null; Ended → exit
@Test fun playEpisodeSwitchesAndKeepsSpeed()               // setSpeed(1.25f); playEpisode(s2e1) → engine.loads.size == 2, speeds.last() == 1.25f
@Test fun seasonEpisodesMarkLocalAndServerWatched()        // s1e1 played on server, s1e2 local finished → both watched
```
- [ ] **Step 2: Run — expect FAIL.**
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run the full suite — expect PASS.**
- [ ] **Step 5: Commit** — `feat(player): up-next countdown, auto-next across seasons, episode switching`

---

### Task 10: Player screen — host, loading/error, video, subtitles in the bars

**Files:**
- Rewrite: `player/PlayerActivity.kt`
- Create: `player/ui/SubtitleLayer.kt`, `player/ui/PlayerScreen.kt`
- Modify: `res/values/strings.xml`
- Test: `player/ui/SubtitlePlacementTest.kt`

**Interfaces:**
- Consumes: `PlayerViewModel` (Tasks 7–9), `ExoPlaybackEngine.exoPlayer` (Task 6), `PlayerArgs.from`.
- Produces:
  ```kotlin
  data class SubtitlePlacement(val primaryBottomPx: Int, val secondaryBottomPx: Int?)   // distance from the screen's bottom edge to the text block's bottom
  fun subtitlePlacement(containerW: Int, containerH: Int, video: VideoSize?, lineHeightPx: Int,
                        dual: Boolean, controlsHeightPx: Int?): SubtitlePlacement
  const val SUBTITLE_BASE_FRACTION = 0.045f   // 100 % size = 4.5 % of screen height
  @Composable fun SubtitleLayer(cues: SubtitleCues, style: SubtitleStyle, placement: SubtitlePlacement, preview: Boolean)
  @Composable fun PlayerScreen(vm: PlayerViewModel, engine: ExoPlaybackEngine)
  companion object { fun intent(context: Context, args: PlayerArgs): Intent }   // on PlayerActivity
  ```
- `subtitlePlacement` rules: fitted video height `vh = min(containerH, containerW × video.h / video.w)` (null video → `vh = containerH`); bar = `(containerH − vh) / 2`; margin = `lineHeightPx / 2`.
  - Bar ≥ `1.5 × lineHeightPx`: single → `primaryBottom = (bar − lineHeightPx) / 2` (centred in the bar); dual → secondary (English) as single, primary (Farsi) at `bar + margin` (just inside the picture).
  - Otherwise: primary at `containerH × 0.06`; dual → secondary at `containerH × 0.06` and primary at `containerH × 0.06 + 2 × lineHeightPx + margin`.
  - `controlsHeightPx != null` → every value raised so the lowest is ≥ `controlsHeightPx + margin` (keeping their spacing).
- Activity: `PlayerArgs.from(intent) ?: finish()`; builds `ExoPlaybackEngine(this, graph.mediaClient)` and the VM via a `viewModelFactory`; `setContent { DroozTheme { PlayerScreen(vm, engine) } }`; `onStop → vm.onStop()`; collects `vm.exits` → `finish()`; `keepScreenOn`. Video via `AndroidView { PlayerView(it).apply { useController = false; subtitleView?.visibility = GONE; player = engine.exoPlayer } }` (resize mode FIT).
- `PlayerScreen` (this task): black background, video, `SubtitleLayer`; Loading = backdrop image (Coil, `graph.imageLoader`) dimmed + title (original language, `TitleText` rules) + spinner; buffering after Ready = small spinner centre; Failed = message + «تلاش دوباره» button (focused) → `vm.retry()`; Back in Loading/Failed → `vm.close()`. Subtitle text: Vazirmatn, size `containerH × SUBTITLE_BASE_FRACTION × sizePercent / 100`, colour from style, box `Color.Black.copy(alpha = backgroundOpacity / 100f)` with small padding and rounded corners, `Shadow(Color.Black, blurRadius = 6f)`, `TextAlign.Center`, `TextDirection.Content`, max width 80 % of screen. `preview = true` with no current cue shows the sample «این یک زیرنویس نمونه است» / "This is a sample subtitle" (for the panel's live preview, Task 11). Persian copy in `strings.xml`.

- [ ] **Step 1: Write the failing tests** (container 1920×1080, lineHeight 60)
```kotlin
@Test fun scopeFilmSingleSitsCentredInBottomBar()  // video 1920×803 (2.39:1): bar = 138 → primaryBottom == 39, secondary null
@Test fun scopeFilmDualPutsEnglishInBarFarsiInPicture() // dual → secondaryBottom == 39, primaryBottom == 168
@Test fun fullFrameUsesSixPercent()                // video 1920×1080 → primaryBottom == 64 (1080×0.06 = 64.8 → 64)
@Test fun fullFrameDualStacksFarsiAboveEnglish()   // → secondary 64, primary 64 + 120 + 30 = 214
@Test fun controlsLiftEverything()                 // scope single, controlsHeightPx 300 → primaryBottom == 330
@Test fun unknownVideoSizeTreatedAsFullFrame()
```
- [ ] **Step 2: Run — expect FAIL** (`--tests '*SubtitlePlacementTest'`).
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run the full suite + `:app:assembleDebug` — expect PASS / BUILD SUCCESSFUL.**
- [ ] **Step 5: Commit** — `feat(player): player screen with loading/error states and letterbox-aware subtitles`

---

### Task 11: Controls overlay, panels, up-next card, remote keys

**Files:**
- Create: `player/ui/Controls.kt`, `player/ui/Panels.kt`, `player/ui/RemoteKeys.kt`
- Modify: `player/ui/PlayerScreen.kt`, `res/values/strings.xml`, `ui/common/UiStrings.kt`
- Test: `player/ui/RemoteKeysTest.kt`

**Interfaces:**
- Consumes: everything on `PlayerViewModel`; `scrubStepMs` (Task 3); `SUBTITLE_SIZES`, `BACKGROUND_OPACITIES`, `SubtitleColor` (Task 5); `SPEEDS`, `SUBTITLE_STEP_MS` (Task 8).
- Produces:
  ```kotlin
  const val CONTROLS_HIDE_MS = 4_000L
  enum class Overlay { NONE, CONTROLS, SUBTITLE_AUDIO, QUALITY, SPEED, EPISODES }
  sealed interface KeyAction {
      data object ShowControls : KeyAction
      data object TogglePlayAndShow : KeyAction
      data class Seek(val deltaMs: Long) : KeyAction          // tap or media FF/REW: immediate snapped seek
      data class Scrub(val deltaMs: Long) : KeyAction         // held key repeat: move the scrub head only
      data object CommitScrub : KeyAction                     // key up after a hold
      data object Back : KeyAction                            // close top overlay, or exit when NONE
      data object None : KeyAction                            // let focus handle it
  }
  /** Pure mapping, so the remote's behaviour is testable. [seekBarFocused] = the seek bar has focus. */
  fun mapKey(keyCode: Int, isDown: Boolean, repeatCount: Int, overlay: Overlay, seekBarFocused: Boolean, scrubbing: Boolean): KeyAction
  ```
- `mapKey` rules (Android `KeyEvent` codes):
  - `overlay == NONE`: DPAD_LEFT/RIGHT down, repeat 0 → `Seek(∓/±10_000)` (and the screen shows controls with the seek bar focused); repeat > 0 → `Scrub(±scrubStepMs(repeat))`; up after scrubbing → `CommitScrub`. DPAD_CENTER/ENTER down → `TogglePlayAndShow`. DPAD_UP/DOWN down → `ShowControls`. BACK up → `Back` (exit).
  - Any overlay: MEDIA_PLAY_PAUSE/PLAY/PAUSE → `TogglePlayAndShow`; MEDIA_FAST_FORWARD / MEDIA_REWIND down → `Seek(±10_000)`; BACK up → `Back`.
  - `CONTROLS` with `seekBarFocused`: LEFT/RIGHT behave as in NONE (tap seeks, hold scrubs, up commits). Otherwise → `None`.
  - Physical RIGHT is always forward (+), LEFT backward (−), regardless of RTL.
- UI (follow `.superpowers/brainstorm/2594803-1793209667/content/player.html` "three moments" mockup; if the file is missing, the description here is sufficient):
  - **Controls** (bottom gradient, top gradient): top = title (original language) + `episodeLabel`; LTR seek bar row: elapsed `H:MM:SS`/`MM:SS`, track (buffered + position, red `#E50914` knob; while scrubbing the knob shows the scrub position and its time), remaining `-MM:SS`. Buttons (tv-material, `droozFocus`): LTR cluster `−۱۰` / ⏯ / `+۱۰`, then «زیرنویس و صدا», «کیفیت: خودکار (1080p)» / the chosen label, «سرعت ۱×», «قسمت‌ها» (series only), «قسمت بعد» (when `hasNext`). Initial focus play/pause (seek bar when opened by LEFT/RIGHT). Auto-hide after `CONTROLS_HIDE_MS` without a key press while playing (never while paused or a panel is open).
  - **Subtitle & audio panel** (left edge, ~30 % width, dark `rgba(18,18,24,.96)`): groups «زیرنویس» (available choices: «فارسی», «English», «فارسی + English», «فارسی (دوبله)», «خاموش»; ✓ on selected), «ظاهر زیرنویس» (size −/+ stepper over `SUBTITLE_SIZES` showing «۱۲۰٪»; colour swatches; background opacity cycle over `BACKGROUND_OPACITIES`), «هماهنگی» (−/+ `SUBTITLE_STEP_MS`, held key repeats, shows «+۰٫۵ ثانیه»), «صدا» (audio options labelled «زبان اصلی», «زبان اصلی ۵.۱», «دوبله فارسی» by label; unknown labels verbatim), «تقویت صدا» on/off. The subtitle layer stays visible behind it with `preview = true` (live preview).
  - **Quality panel**: «خودکار (‹playingHeight›p)» + `qualities` labels; ✓ on current. **Speed panel**: `SPEEDS` as «۰٫۵×» … «۲×».
  - **Episodes strip**: horizontal row over the bottom of `seasonEpisodes` (label + ✓ when watched; current one marked and initially focused) → `playEpisode`.
  - **Up-next card** (bottom-left, 24 % width): episode label, «پخش خودکار تا ‹n› ثانیه دیگر», buttons «▶ پخش الان» (focused → `playNext`) and «تماشای تیتراژ» (→ `dismissUpNext`); Back = dismiss. It takes focus when it appears even if controls are hidden.
  - Subtitles lift above the controls when they're visible (`controlsHeightPx` into `subtitlePlacement`).

- [ ] **Step 1: Write the failing tests**
```kotlin
@Test fun hiddenRightTapSeeksForward()  = assertEquals(KeyAction.Seek(10_000), mapKey(KEYCODE_DPAD_RIGHT, true, 0, Overlay.NONE, false, false))
@Test fun hiddenLeftTapSeeksBack()      = assertEquals(KeyAction.Seek(-10_000), mapKey(KEYCODE_DPAD_LEFT, true, 0, Overlay.NONE, false, false))
@Test fun holdScrubsWithAcceleration()  // RIGHT repeat 5 → Scrub(30_000); LEFT repeat 12 → Scrub(-60_000)
@Test fun releaseAfterHoldCommits()     // RIGHT up, scrubbing = true → CommitScrub; RIGHT up, scrubbing = false → None
@Test fun centerTogglesWhenHidden()     // CENTER down NONE → TogglePlayAndShow; CENTER down CONTROLS → None (button click handles it)
@Test fun arrowsMoveFocusInControlsUnlessOnSeekBar() // RIGHT down CONTROLS seekBarFocused=false → None; true → Seek(10_000)
@Test fun mediaKeysWorkEverywhere()     // MEDIA_PLAY_PAUSE in SUBTITLE_AUDIO → TogglePlayAndShow; MEDIA_FAST_FORWARD in NONE → Seek(10_000)
@Test fun backOnlyOnKeyUp()             // BACK down → None; BACK up → Back
```
- [ ] **Step 2: Run — expect FAIL** (`--tests '*RemoteKeysTest'`).
- [ ] **Step 3: Implement** `RemoteKeys.kt`, then the composables, wiring `onPreviewKeyEvent` at the screen root through `mapKey`.
- [ ] **Step 4: Run the full suite + `:app:assembleDebug` — expect PASS / BUILD SUCCESSFUL.**
- [ ] **Step 5: Commit** — `feat(player): controls overlay, subtitle/audio, quality, speed and episode panels, up-next card, remote keys`

---

### Task 12: Title page hands off to the player; install

**Files:**
- Modify: `ui/title/TitleViewModel.kt`, `ui/title/TitleScreen.kt`, `ui/DroozNavHost.kt`
- Test: `ui/title/TitleViewModelTest.kt`

**Interfaces:**
- Consumes: `PlayerArgs`, `PlayerActivity.intent(context, args)`.
- Produces: `PlayEvent` becomes `sealed interface PlayEvent { data class Start(val args: PlayerArgs) : PlayEvent }`; `TitleViewModel.play(episode: Episode? = null)` emits `Start(PlayerArgs(ref, ep.streamId, dub))` immediately (no network) and sets `playedSinceLoad = true`. Removed: `PlayBusy`, `TitleUiState.playBusy`, `cancelPlay()`, `playFailure`, `PlayEvent.Failed`, the file's `STREAM_HEIGHT`, and the busy spinners on the primary button / episode tiles. `TitleScreen` keeps collecting `playEvents` only while resumed and calls `onStartPlayer(args)`; `DroozNavHost` passes `{ context.startActivity(PlayerActivity.intent(context, it)) }`. Debug `debug_play` keeps working through `vm.play()`.

- [ ] **Step 1: Update tests first.** Replace `playShowsBusyAndIgnoresRepeats`, `cancelledPlayNeverStarts`, `playFailureMessages` with:
```kotlin
@Test fun playEmitsArgsForPrimaryEpisode()   // series with s1e1 played → play() → Start(PlayerArgs(ref, s1e2.streamId, dub = false)); api.openStream never called
@Test fun playTileEmitsThatEpisode()         // play(s2e1) → Start(args with s2e1.streamId)
@Test fun dubSelectionPlaysDubStream()       // (rewrite existing) toggleDub(); play() → args.dub == true and streamId from catalog.dub
```
Keep `resumeAfterPlayRevalidatesInPlace` etc. passing (they now rely on `play()` setting `playedSinceLoad`).
- [ ] **Step 2: Run — expect FAIL** (`--tests '*TitleViewModelTest'`).
- [ ] **Step 3: Implement** the changes; delete now-unused strings/UiStrings only if nothing else uses them (`PLAY_FAILED*` are reused by the player).
- [ ] **Step 4: Run the full suite + `:app:assembleDebug` — expect PASS / BUILD SUCCESSFUL.**
- [ ] **Step 5: Install on the TV (no playback):** `adb -s 192.168.88.174:35875 install -r app/build/outputs/apk/debug/app-debug.apk` → `Success`; then `adb -s 192.168.88.174:35875 shell am start -n com.alikarami.drooz/.ui.MainActivity` and take one screenshot to confirm Home renders. Do **not** press OK/Play; leave the app on Home. (If the device is unreachable, record it in the report — the owner verifies playback.)
- [ ] **Step 6: Commit** — `feat(title): hand playback to the player (stream opens there, Back cancels it)`

---

## Owner verification after the plan (not agent work)

Play an episode partway through a series: loading screen → resumes; ←/→ ±10 s, hold to scrub; «زیرنویس و صدا» size/colour/background/sync with live preview; «فارسی + English» (Farsi in the picture, English in the bar on a scope film); 5.1 picked on the soundbar; Audio Boost toggle; quality list with sizes; up-next card at the end → next episode; Back → title page shows the new progress / ✓.
