package com.alikarami.drooz.ui.common

/** Persian copy that ViewModels compute (JVM-testable, single source). Static screen copy lives in strings.xml. */
object UiStrings {
    const val WATCH_AGAIN = "تماشای دوباره"
    const val FILM = "پخش"
    const val DUB = "دوبله"
    const val SUBTITLE = "زیرنویس"
    const val PLAY_FAILED = "پخش شروع نشد"
    const val PLAY_FAILED_NETWORK = "اتصال به سایت برقرار نشد؛ دوباره تلاش کنید"
    const val PLAYBACK_FAILED = "پخش با مشکل روبه‌رو شد"

    fun continueWatching(season: Int, episode: Int): String = "ادامه فصل $season قسمت $episode".toPersianDigits()
    /** Not-started series: names the episode that will play; the season only when it isn't the first. */
    fun playEpisode(season: Int, episode: Int): String =
        (if (season == 1) "پخش قسمت $episode" else "پخش فصل $season قسمت $episode").toPersianDigits()
    fun minutes(n: Int): String = "$n دقیقه".toPersianDigits()
}
