# DRooz for Android TV

An unofficial, native Android TV app for **DLRooz** ([robin.robert-redford.net](https://robin.robert-redford.net/)), the Persian movie and series site. It turns your existing DLRooz account into a TV experience: big artwork, a Persian right-to-left interface, and a player built for the remote.

> This is a personal hobby project. It is not affiliated with or endorsed by DLRooz. You need your own paid DLRooz account; the app does not bypass anything on the site.

## What it does

- **Browse like a streaming app.** Home rows from the site (featured, new films, updated series, and more), a hero area that follows focus, full grids for every list, and search.
- **Title pages.** Synopsis, IMDb rating, cast, awards, season tabs with episode tiles, original or Persian-dubbed audio, and your site watchlist («لیست من»).
- **A full player** with everything the site's web player has:
  - **Quality:** the site's quality list with file sizes, plus Automatic.
  - **Audio:** 5.1 audio when your TV or soundbar supports it, and Audio Boost.
  - **Subtitles:** Farsi, English, or both at once, with adjustable size, colour, background and timing.
  - **Navigation:** resume where you stopped, ±10 seconds on ←/→ (hold to scrub), and next-episode autoplay.
- **Menus in Persian** (RTL); titles keep their original language.

## Requirements

- An Android TV or Google TV device on **Android 12 or newer**, such as a Google TV Streamer or Chromecast with Google TV.
- A **DLRooz account**. You sign in once with your username and password; the site may also ask for your mobile number. Your credentials stay on the TV, encrypted with the Android Keystore, and are only sent to the site.
- The site allows a limited number of devices per account (currently 3). The TV counts as one of them.

## Install

There are no prebuilt downloads yet, so you build the APK yourself and install it on the TV over your local network.

### 1. Build the APK

You need **JDK 17 or newer** and the **Android SDK**. The easiest route is to install [Android Studio](https://developer.android.com/studio), which bundles both.

```bash
git clone https://github.com/AliKarami/DRooz-AndroidTV.git
cd DRooz-AndroidTV
./gradlew assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

### 2. Allow installs on the TV

1. On the TV, go to **Settings → System → About** and press OK on **Android TV OS build** seven times until it says you are a developer.
2. Go to **Settings → System → Developer options** and turn on **USB debugging** (on Google TV this also enables wireless debugging).
3. Note the TV's IP address from **Settings → Network & Internet**.

### 3. Install over the network

From the computer where you built the APK (`adb` comes with the Android SDK, in `platform-tools`):

```bash
adb connect <TV-IP-address>:5555
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The first time, the TV asks whether to allow debugging from this computer. Choose **Always allow**.

If the TV shows a pairing code instead (**Wireless debugging → Pair device with pairing code**), run `adb pair <ip>:<pairing-port>` with that code first. Then connect to the port shown on the Wireless debugging screen.

The app appears in the TV's app list as **دی‌ال‌روز**. To update it later, pull the latest code and repeat steps 1 and 3; your login and settings are kept.

## Using it

- **First launch:** sign in with your DLRooz account. You stay signed in from then on.
- **Remote, on browse screens:** the arrow keys move around, OK opens a title, and Back goes back.
- **Remote, in the player:**
  - OK shows the controls.
  - ←/→ jump 10 seconds; hold them to scrub faster.
  - «زیرنویس و صدا» opens subtitle and audio options.

## For developers

- **Code:** Kotlin, Jetpack Compose for TV (`androidx.tv:tv-material`), Media3 ExoPlayer, OkHttp, and Jsoup for reading the site's pages.
- **Tests:** `./gradlew testDebugUnitTest`. The site parsers are tested against saved pages in `app/src/test/resources/fixtures`, which are sanitized so that no personal tokens are committed.
- **Docs:** design and implementation plans are in [`docs/superpowers`](docs/superpowers).

Never commit cookies, tokens, credentials or captured stream URLs. `scripts/sanitize_fixture.py` cleans a captured page before it becomes a test fixture.
