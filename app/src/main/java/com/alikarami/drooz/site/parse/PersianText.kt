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

    fun digits(s: String): String = buildString(s.length) {
        for (c in s) {
            val p = PERSIAN.indexOf(c).takeIf { it >= 0 } ?: ARABIC.indexOf(c).takeIf { it >= 0 }
            append(if (p != null) '0' + p else if (c == '٫') '.' else c)
        }
    }

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
}
