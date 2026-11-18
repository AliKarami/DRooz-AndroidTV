package com.alikarami.drooz.site.parse

import com.alikarami.drooz.site.model.Rating

/** Helpers for the site's Persian-digit metadata strings. */
object PersianText {
    private const val PERSIAN = "۰۱۲۳۴۵۶۷۸۹"
    private const val ARABIC = "٠١٢٣٤٥٦٧٨٩"
    private val SCORE = Regex("""([\d.]+)\s*از\s*10""")
    private val VOTES = Regex("""با\s*([\d.]+)\s*(هزار|میلیون)?\s*رای""")
    private val MINUTES = Regex("""(\d+)\s*دقیقه""")
    private val CRITICS = Regex("""منتقدین\s*:\s*(\d+)""")

    private val THOUSANDS = Regex("""(?<=\d)[٬،,](?=\d)""")

    /** Persian/Arabic digits to ASCII, `٫` to `.`; thousands separators between digits are dropped. */
    fun digits(s: String): String = buildString(s.length) {
        for (c in s) {
            val p = PERSIAN.indexOf(c).takeIf { it >= 0 } ?: ARABIC.indexOf(c).takeIf { it >= 0 }
            append(if (p != null) '0' + p else if (c == '٫') '.' else c)
        }
    }.replace(THOUSANDS, "")

    private val SPACES = Regex("""\s+""")

    /**
     * The form site text is compared in: Arabic ي/ى/ك become Persian ی/ک, ZWNJ and NBSP become a space,
     * bidi marks and tatweel are dropped, and runs of spaces collapse. Used on both sides of a match.
     */
    fun normalize(s: String): String = buildString(s.length) {
        for (c in s) when (c) {
            'ي', 'ى' -> append('ی')
            'ك' -> append('ک')
            '\u200c', '\u00a0', '\u202f', '\u2009' -> append(' ')
            '\u200d', '\u200e', '\u200f', '\u0640', '\ufeff' -> Unit
            else -> append(c)
        }
    }.replace(SPACES, " ").trim()

    fun rating(s: String): Rating? {
        val t = digits(s)
        val score = SCORE.find(t)?.groupValues?.get(1)?.toDoubleOrNull() ?: return null
        val v = VOTES.find(t) ?: return null
        val base = v.groupValues[1].toDoubleOrNull() ?: return null
        val mult = when (v.groupValues[2]) { "هزار" -> 1_000; "میلیون" -> 1_000_000; else -> 1 }
        return Rating(score, Math.round(base * mult).toInt())
    }

    fun minutes(s: String): Int? = MINUTES.find(digits(s))?.groupValues?.get(1)?.toIntOrNull()

    fun criticsScore(s: String): Int? = CRITICS.find(digits(s))?.groupValues?.get(1)?.toIntOrNull()

    private val ORDINALS = mapOf(
        "اول" to 1, "یکم" to 1, "دوم" to 2, "سوم" to 3, "چهارم" to 4, "پنجم" to 5, "ششم" to 6, "هفتم" to 7,
        "هشتم" to 8, "نهم" to 9, "دهم" to 10, "یازدهم" to 11, "دوازدهم" to 12, "سیزدهم" to 13, "چهاردهم" to 14,
        "پانزدهم" to 15, "شانزدهم" to 16, "هفدهم" to 17, "هجدهم" to 18, "هیجدهم" to 18, "نوزدهم" to 19,
        "بیستم" to 20, "سی ام" to 30, "سیام" to 30, "چهلم" to 40,
    )
    private val TENS = mapOf("بیست" to 20, "سی" to 30, "چهل" to 40)

    /** «سوم» → 3, «بیست و یکم» → 21, «۳» → 3; null for anything else. */
    fun ordinal(word: String): Int? {
        val w = normalize(word)
        digits(w).toIntOrNull()?.let { return it }
        ORDINALS[w]?.let { return it }
        val parts = w.split(" و ")
        if (parts.size != 2) return null
        val tens = TENS[parts[0]] ?: return null
        val unit = ORDINALS[parts[1]]?.takeIf { it in 1..9 } ?: return null
        return tens + unit
    }

    private val EPISODE_BADGE = Regex("""قسمت (\d+) فصل (.+)""")

    /**
     * The site's series badge «قسمت ۰۷ فصل سوم» as (season 3, episode 7); null when it reads otherwise
     * (e.g. «قسمت ۰۷ فصل سوم / ۷۰», which then keeps its own wording).
     */
    fun episodeBadge(label: String): Pair<Int, Int>? {
        val m = EPISODE_BADGE.matchEntire(digits(normalize(label))) ?: return null
        val episode = m.groupValues[1].toIntOrNull() ?: return null
        val season = ordinal(m.groupValues[2]) ?: return null
        return season to episode
    }
}
