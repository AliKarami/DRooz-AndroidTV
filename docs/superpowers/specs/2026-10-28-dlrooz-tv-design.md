# DLRooz TV — Android TV client for robin.robert-redford.net

**Date:** 2026-10-28 · **Status:** draft for approval

## 1. Intent

The owner watches movies and series from the DLRooz site (`https://robin.robert-redford.net/`, a paid-account site) and has no TV app for it. This is a personal, native Android TV app for their **Google TV Streamer (4K)** connected to an LG TV + LG soundbar. UX quality is the top priority: it must feel like Netflix / Google TV, not like a website in a box.

How it's used, in order of frequency:

1. **Continue a series in progress.** Open app → the next episode is right there → one press plays it.
2. **Guests over → browse films together.** Big artwork, enough info on screen to decide as a group, quick scrolling.
3. **Out of series → explore** new releases and the site's recommendations.

The household watches **original audio, usually with Persian subtitles**, sometimes English, sometimes **both at once**. Same habits for films and series. Subtitle appearance (size/color) matters a lot.

## 2. Goals, non-goals, anti-goals

**Goals**
- Polished 10-foot UI, Persian **RTL** chrome, titles kept in original language (usually English), Persian descriptions as-is.
- Zero-friction resume: "Continue watching" first row, focused on launch; OK on a series card plays the right episode at the right second.
- Player with **every feature of the site's web player** (§6), and nothing that blocks a remote.
- Watch state consistent with the site: episodes finished on the laptop show ✓ on TV and vice versa.

**Non-goals (v1)** — *the first two are the designer's call, not discussed with owner*
- Offline downloads to the device.
- Comments, likes, ratings, VIP purchase, invite, profile editing.
- Episode titles / stills / synopses from external databases (TMDB) — owner explicitly chose site-data-only (numbered tiles).
- Multiple profiles, phone/tablet layouts, Play Store distribution (sideloaded APK).

**Anti-goals** (would be failures even if "working")
- Asking the user to pick quality/server before playing.
- Anything that feels laggy: focus jank, blank screens while loading, spinners where cached data could show.
- Looking like a new device repeatedly (the site's terms: one person, max 3 personal devices; owner says slots are plentiful, but don't waste them). The app keeps its cookies (`sf`, `cf`) forever and logs in **only** when the site actually rejects the session (§7.4), reusing the same `sf`.
- Silently marking things watched on the owner's account that they didn't watch (e.g. prefetching stream pages or video segments for metadata).

## 3. Constraints

- **Target device:** Google TV Streamer 4K (Android TV 14, MediaTek, 4 GB RAM; 4K HDR/Dolby Vision, AV1; HDMI → LG TV → eARC → LG soundbar). Optimise for it; older boxes not required.
- **No official API.** Everything is HTML scraping + a few JSON endpoints. Site markup can change → parsing must be isolated in one layer with tolerant parsers and clear errors.
- **Network:** owner is in Iran behind a MikroTik router doing split routing: Iranian destinations go direct, everything else through a VPN. So the site (Cloudflare) is reached via VPN, the video CDN (`ast4.file-anycast.com`, 87.236.x) likely direct, and Google services (voice recognition) via VPN. All research in §4 was done from a machine on this same network, so it reflects the TV's real paths. Expect flaky connections (VPN drops): aggressive caching, retries, graceful offline states.
- **Sideloaded** debug/release APK built locally (`~/Projects/DR-Android-TV`).

## 4. Site contract (verified 2026-10-28 with curl, logged in)

All requests: browser-like User-Agent, cookie jar, and **always cookie `scr=1`** (normally set by JS; without it the server returns a JS redirect stub). Cloudflare does not challenge plain clients.

### 4.1 Login (two step, multipart POST to `/user/login/`)
1. `username`, `password` → if the account needs device confirmation, response page contains `<input type='hidden' name='recaptchaPass' value='<32hex><unixts>'>` and a `mobile` field with masked hint like `09xx***xxxx`, text «لطفا شماره موبایل خود را کامل کنید».
2. `username`, `password`, `recaptchaPass`, `mobile=<full number>` → `Set-Cookie: cf=…` (1-year). Page says «در حال انتقال».
- Wrong credentials → page contains «نام کاربری یا رمز عبور اشتباه می باشد».
- Logged-in = presence of `cf` cookie (site JS also treats `cf` as the login flag). Cookie `sf` is set on first visit (device/session id) — **persist it forever**; likely what the 3-device rule counts.
- No SMS OTP was required. If a future response contains an unknown extra field, show it to the user rather than failing silently.
- Logout: `/user/logout/`.

### 4.2 Content
- **Home** `/`: featured carousel (`.carousel-item` → `/post/...` + backdrop `/img/752-423/<hash>`), section «مشاهدات اخیر پخش آنلاین» (**recently streamed by this account**, server-side, title-level, ordered), «فیلم های برگزیده», «سریال های بروز شده» (with "قسمت ۰۷ فصل سوم", status «در حال پخش»/«پایان فصل»), film cards with full metadata.
- **Lists** `/archive/...` (category/genre/country/playlist/series), paginated as `/archive/category/new-films/<N>/`. Each item already contains title, year, duration, IMDb rating+votes, critics score, genres, country, director, stars, Persian synopsis, poster `/img/<w>-<h>/<hash>`. Menu of all lists is in the site's nav (films, series, dubbed, kids, anime, IMDb Top 250 series, popular series, box office, etc.).
- **Search** `/search/?s=<q>` (English titles work; Persian title search returned nothing in a test).
- **Title page** `/post/{film|series}/{id}/{slug}/` (~67 KB gzipped):
  - Metadata block (same fields as list + channel, awards, update date, «قسمت ۰۹ فصل اول / پایان فصل»).
  - `var STREAM={...};` JSON: `{"main": seasons, "dub": seasons?, "subtitle": bool}`; seasons → episodes (films: `[[ep]]`), each episode `{id: streamId, videos:[{id,width,height,size}], audios:[{id,lang:"original"|"dubbed-fa",channels}], subtitles:[{id,lang:"fa"|"en",gpt}], played?: true}`. **`played` is the server-side watched flag** (set by the server, apparently from CDN segment fetches — the CDN URLs embed the user id; threshold unknown).
  - «آثار پیشنهادی» = suggested titles (links to posts).
  - Watchlist / favourite / watched buttons: `GET /json/userWatchlist/{postId}/{state}/` (state values e.g. `watched`; read the button markup for the others). Watchlist page `/user/watchlist/`.
  - `?cleanWatchHistory` clears a series' watched ticks (not needed in v1).
  - Download links `/dln/<userToken>/<postId>/<fileId>[/<ep>]/<name>.mkv?lc=|nl|u` → 302 to `iedge*.xfer-amazon.com/dl/...` (range requests OK, no cookies needed). Not used in v1 except possibly as a playback fallback.
- **Images:** `/img/<W>-<H>/<hash>[.webp]` resizes to **any** requested size → request exactly the pixel size needed. Trailer thumbs `/img/<W>-<H>/trailer/<hash>.jpg.webp` (low-res source).

### 4.3 Stream page `/stream/{streamId}/{postId}/?lang=original|dubbed-fa&h=<height>`
Opening it is what "plays" a title on the site (it shows up in recently-streamed). Contains:
- `file: '/json/m3u8/{streamId}/{postId}/{userToken}/master.m3u8?key=…&expire=…&load'` — **valid ~1 minute**: fetch the stream page immediately before playback, never cache it.
- Master playlist: HLS with separate audio groups (`original`, `dubbed-fa`, `original-6CH` for 5.1) and video variants on `https://ast4.file-anycast.com/verify/halt-video-<hash>-<userId>-<n>/...`. CDN needs **no cookies/referer**, CORS `*`.
- `tracks`: captions `Farsi`, `English`, `English + Farsi` (`...sub.srt?stream=X&second=Y`), and for dubbed audio a `Farsi for Dub` track; SRT format.
- `qualityLabels` e.g. `'2475':'1080 [ 2.48 GB ]'`, `image:` 1920×1080 backdrop, `title:`.
- `Gains = [[segmentSeconds, gain], ...]`, `minGain`, `maxGain` — per-segment loudness map for "Audio Boost".
- `IFrames[q] = [...]` keyframe times per quality.
- Error reporting: `GET /json/logStreamError/{streamId}/?event=<setupError|error>&msg=<code>` (max once per minute).
- `/json/device/?width=&height=&pixelRatio=` is called by the site with screen info (send once after login: 3840×2160, 1).

## 5. Screens (approved via mockups)

Design language: dark (#0b0b0f), Netflix-like; font **Vazirmatn** for Persian, system sans for Latin titles; red accent (#e50914) for progress/selection; Persian digits for numbers in Persian strings. Focus = scale ~1.08–1.1 + white outline + soft shadow, 150–200 ms animations. Everything RTL (rows start at the right, "next" is ←).

### 5.1 Home — "Netflix style" (owner chose option B)
- **Right-edge icon rail** (collapsed): Search, Home, Series, Films, My list, Settings. Pressing → into it expands with labels.
- **Focus-following hero:** top ~60 % shows the focused title's backdrop (crossfade), English title, IMDb badge, year, duration, genres, chips (دوبله / زیرنویس / 4K), Persian synopsis (3 lines). Info comes from list data (instant); backdrop loaded lazily (§7.3).
- **Rows** (horizontal, poster 2:3 except first row):
  1. **ادامه تماشا** (Continue watching) — landscape 16:9 cards with red progress bar and "فصل ۲ · قسمت ۷". App opens focused on its first card. OK = play immediately (§7.2). Long-press OK / menu = open title page.
  2. Featured films, recently updated series, new films, new series, popular series, IMDb top series, Persian-dubbed, kids… (*order is the designer's guess; make it a single list in code*).
- Lists open as a full grid with infinite scroll (site pagination).

### 5.2 Title page (series & films) — owner chose option A (site data only)
- Full-bleed backdrop, title, IMDb, year, channel, genres, season count, chips, awards line (🏆 …), synopsis, creator/director + stars.
- **Primary button adapts:** «پخش قسمت ۱» / «ادامه فصل X قسمت Y» / «تماشای دوباره». Films: «پخش» or «ادامه (۹۴ دقیقه باقی‌مانده)».
- **My list** button (site watchlist, synced).
- **Original ↔ دوبله** segmented switch, shown only if `dub` exists; remembered per title.
- **Season tabs + numbered episode tiles**: ✓ for watched (server `played` ∪ local finished), red bar for partial (local), duration under number. Initial focus = next unwatched episode.
- Second tab: «آثار پیشنهادی» (site suggestions) as poster row.

### 5.3 Search
On-screen Persian/English keyboard + **voice search via the remote's mic** (in-app speech recognition), live results as you type (debounced), results grid. *(Designer's call: not discussed in detail.)*

### 5.4 Login (first run only)
Username, password, then mobile number when the site asks, typed with the remote once. Errors in Persian. *(Designer's call; a phone-QR login is a possible later nicety.)*

### 5.5 Settings
Account (logout), subtitle appearance (same as in player), default audio (original), audio boost default, clear app cache, app version.

## 6. Player — "literally everything the site's player has"

Owner reviewed this inventory and confirmed it's complete.

| # | Feature | Behaviour on TV |
|---|---|---|
| 1 | Quality list with sizes | Menu shows the site's labels ("1080 [2.48 GB]") + **Automatic** (default, ABR). |
| 2 | Audio tracks incl. 5.1 | original / dubbed-fa / original-6CH; auto-pick 6CH when output supports multichannel (soundbar via eARC). |
| 3 | Subtitles Farsi / English / **English + Farsi** / **Farsi for Dub** | All; "Farsi for Dub" auto-selected when dubbed audio is chosen. |
| 4 | Remember preferred subtitle language | Global preference (default Farsi). |
| 5 | Subtitle look | Size ±, colour (white / yellow / …), background box opacity, font Vazirmatn, drop shadow/edge. Live preview behind the panel. Global. |
| 6 | Subtitle sync | ±0.5 s steps (hold = repeat); remembered per episode. |
| 7 | Subtitles in the letterbox bars | When video aspect leaves bars, place subs in the bottom bar; lift them when controls are visible. Dual mode: Persian in picture, English in bar (as on the site). |
| 8 | Audio Boost | Per-segment gain from `Gains` (smoothed like the site: approach target ~1/18 per update, clamp min/max). Toggle, remembered. Only possible when audio is decoded to PCM, not when bitstreaming. |
| 9 | Resume | Local position, resume only if > 30 s and not near the end. |
| 10 | Auto-next | Near the end: "Next episode" card with countdown (OK = now, Back = watch credits); rolls into next season. |
| 11 | Playback speed | 0.5–2×. |
| 12 | ±10 s | Remote ←/→ (RTL-aware: decide and keep consistent — designer's call: physical right = forward, as on every TV app); hold = accelerating scrub. |
| 13 | Keyframe-snapped seeking | Use Media3 `SeekParameters.CLOSEST_SYNC` (or the `IFrames` list) so seeks don't stall. |
| 14 | Error reporting | Call `/json/logStreamError/` like the site (≤ 1/min). |
| 15 | Chromecast | N/A. |

Controls overlay: title + "فصل ۱ · قسمت ۴" top; seek bar (no thumbnails — site has none); buttons: −10 s, play/pause, +10 s, «زیرنویس و صدا» (side panel), «کیفیت», «قسمت‌ها» (episode strip overlay), «قسمت بعد». Auto-hide after 4 s. Back closes overlay, second Back exits to title page.

## 7. Behaviour details

### 7.1 Watch state model
- **Server:** recently-streamed titles (home section) + per-episode `played` (title page `STREAM`).
- **Local (Room):** per stream id: position, duration, updatedAt, finished flag, subtitle sync offset; per title: chosen audio (orig/dub).
- **Watched ✓** = server `played` OR local finished (≥ 90 % or reached credits countdown). Local wins until the server catches up.

**Accepted limits of cross-device sync (owner agreed):** the site stores no in-episode position server-side, so an episode half-watched on the laptop starts from the beginning on the TV (or from the TV's own saved position). TV → laptop ✓ relies on the server's `played` flag being set by TV playback through the same CDN URLs — **unverified; check early** by watching one real episode on the TV and re-reading its title page. If it isn't set, report to the owner before inventing a workaround (no silent writes to their history).

### 7.2 Continue watching
1. Take the server's recently-streamed list (ordered) merged with local history (by `updatedAt`), de-duplicated by post id.
2. For each, fetch (cached) title page → `STREAM`:
   - Series: if some episode is locally in progress → that episode + progress; else first episode after the last watched one (crossing seasons). Fully watched with no newer episodes → drop (or move to the end with «فصل جدید به زودی» if status is «در حال پخش» — designer's call).
   - Film: local position → show with "X دقیقه باقی‌مانده"; finished → drop.
3. OK on card → fetch stream page → play at the resume position.

### 7.3 Backdrops for the focus-following hero (known risk — spike first)
List data has posters only. Sources in priority: home-carousel backdrop (high quality) → stream-page `image:` if we've played it before (cached) → title page trailer thumbnail `/img/1920-1080/trailer/<hash>` (low-res source; hide with gradients/blur) → blurred poster. Title pages are fetched on **focus dwell ≥ 300 ms**, cached (memory + disk, ~24 h), max 2 concurrent. Never fetch stream pages for metadata (anti-goal: it counts as watching).

### 7.4 Caching & resilience
Home/list HTML cached and shown instantly on next launch, refreshed in background (stale-while-revalidate). Images via disk cache at exact sizes. Clear Persian error states with retry; session-expiry detection (redirect to `/user/login/` on a page that needs auth) → re-login with stored credentials (encrypted) **keeping the existing `sf` cookie**, then retry once. If the site asks for the mobile number again, use the stored one; if it asks for anything unknown, show the login screen instead of guessing.

### 7.5 Development against the real site
Build and test parsers against **saved HTML fixtures** captured from the one existing dev session (no new logins from emulators). Live testing happens on the Streamer itself, which logs in once as its own device.

## 8. Technical approach (designer's calls)

- Kotlin, single-module app (`app/`), Gradle KTS, minSdk 31, target latest.
- **UI:** Jetpack Compose + `androidx.tv:tv-material`; app-wide `LayoutDirection.Rtl`; bundled Vazirmatn (OFL).
- **Playback:** Media3 ExoPlayer (HLS; progressive MKV as fallback), custom Compose controls, Media3 `SubtitleView`(s) styled via `CaptionStyleCompat`; audio boost via a custom `AudioProcessor` (gain) in the renderers factory.
- **Network:** OkHttp with persistent cookie jar (encrypted DataStore), fixed desktop-Chrome UA, `scr=1` interceptor, login/session interceptor; Jsoup parsers in a `site/` package with fixture-based unit tests using saved HTML from the real site.
- **Images:** Coil 3 with a size-aware URL mapper for `/img/W-H/`.
- **Storage:** Room (watch state, prefs), DataStore (settings, credentials encrypted with Android Keystore).
- **DI/arch:** ViewModels + Kotlin Flows; Hilt or manual DI — builder's choice.

## 9. Open items for the builder

- Exact threshold for server `played` (doesn't affect design; local ✓ covers the gap).
- Confirm 5.1 path: AAC 6ch decoded → multichannel PCM over HDMI → LG eARC soundbar. If PCM multichannel isn't accepted, fall back to stereo.
- Verify `/json/userWatchlist/` state values for add/remove watchlist and favourite from the title page markup.
- Dual-subtitle rendering: either the site's merged `&second=` SRT or two separate SRTs in two views — choose whichever positions correctly.
