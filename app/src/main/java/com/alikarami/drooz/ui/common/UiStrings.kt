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
    fun upNextLabel(season: Int, episode: Int): String = "قسمت بعدی · فصل $season قسمت $episode".toPersianDigits()
    fun minutes(n: Int): String = "$n دقیقه".toPersianDigits()

    // ---- player ----
    /** 0.5 → «۰٫۵×», 1 → «۱×». */
    fun speed(x: Float): String = decimal(x.toString()) + "×"
    fun speedButton(x: Float): String = "سرعت ${speed(x)}"
    /** Subtitle sync: 500 → «+۰٫۵ ثانیه», −1500 → «−۱٫۵ ثانیه», 0 → «۰ ثانیه». */
    fun syncOffset(ms: Long): String {
        val sign = when {
            ms > 0 -> "+"
            ms < 0 -> "−"
            else -> ""
        }
        return "$sign${decimal((kotlin.math.abs(ms) / 1000.0).toString())} ثانیه"
    }
    /** Quality names stay Latin, as the site shows them («1080p»). */
    fun qualityAuto(playingHeight: Int?): String = if (playingHeight == null) "خودکار" else "خودکار (${playingHeight}p)"
    fun qualityButton(label: String): String = "کیفیت: $label"
    fun percent(n: Int): String = "$n٪".toPersianDigits()
    fun autoPlayIn(seconds: Int): String = "پخش خودکار تا $seconds ثانیه دیگر".toPersianDigits()
    /** Audio rendition names from the stream, by label text; unknown labels verbatim. */
    fun audioLabel(label: String): String = when {
        label.contains("6CH") -> "زبان اصلی ۵.۱"
        label.contains("dubbed") -> "دوبله فارسی"
        label.contains("original") -> "زبان اصلی"
        else -> label
    }

    private fun decimal(plain: String): String =
        java.math.BigDecimal(plain).stripTrailingZeros().toPlainString().replace('.', '٫').toPersianDigits()
}

/** Seek-bar clock: «MM:SS», or «H:MM:SS» from an hour (Latin digits, as in the mockup). */
fun formatClock(ms: Long): String {
    val total = maxOf(0L, ms) / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) String.format(java.util.Locale.ROOT, "%d:%02d:%02d", h, m, s) else String.format(java.util.Locale.ROOT, "%02d:%02d", m, s)
}
