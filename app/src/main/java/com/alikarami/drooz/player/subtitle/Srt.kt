package com.alikarami.drooz.player.subtitle

/** One subtitle cue; [text] has its lines joined with `\n` and formatting tags removed. */
data class SubCue(val startMs: Long, val endMs: Long, val text: String)

/** Lenient SRT parser: malformed blocks are skipped, non-SRT input yields no cues. */
object SrtParser {
    private val TIMING = Regex(
        """(\d{1,2}):(\d{2}):(\d{2})[,.](\d{1,3})\s*-->\s*(\d{1,2}):(\d{2}):(\d{2})[,.](\d{1,3})""",
    )
    private val TAGS = Regex("""<[^>]+>|\{\\[^}]*\}""")

    fun parse(srt: String): List<SubCue> {
        return try {
            val lines = srt.removePrefix("﻿").lines().map { it.trimEnd('\r') }
            val cues = ArrayList<SubCue>()
            var i = 0
            while (i < lines.size) {
                val m = TIMING.find(lines[i])
                i++
                if (m == null) continue
                val g = m.groupValues
                val body = ArrayList<String>()
                while (i < lines.size && lines[i].isNotBlank() && TIMING.find(lines[i]) == null) {
                    body += lines[i].replace(TAGS, "").trim()
                    i++
                }
                // Block without a blank separator: the trailing bare number is the next block's index.
                if (i < lines.size && lines[i].isNotBlank() && body.lastOrNull()?.all { it.isDigit() } == true) {
                    body.removeAt(body.lastIndex)
                }
                val text = body.filter { it.isNotEmpty() }.joinToString("\n")
                if (text.isNotBlank()) {
                    cues += SubCue(ms(g[1], g[2], g[3], g[4]), ms(g[5], g[6], g[7], g[8]), text)
                }
            }
            cues.sortedBy { it.startMs }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** Millis digits are a fraction: "5" means 500, "50" means 500, "500" means 500. */
    private fun ms(h: String, m: String, s: String, frac: String): Long =
        (h.toLong() * 3600 + m.toLong() * 60 + s.toLong()) * 1000 + frac.padEnd(3, '0').toLong()
}

/** Cues indexed by start time for the 100 ms position poll. */
class SubtitleTimeline(cues: List<SubCue>) {
    private val sorted = cues.sortedBy { it.startMs }
    private val maxCueMs = sorted.maxOfOrNull { it.endMs - it.startMs } ?: 0L

    val isEmpty: Boolean get() = sorted.isEmpty()

    /** Every cue with `startMs <= positionMs < endMs`, in start order. */
    fun at(positionMs: Long): List<SubCue> {
        // First index whose start is > positionMs.
        var lo = 0
        var hi = sorted.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (sorted[mid].startMs <= positionMs) lo = mid + 1 else hi = mid
        }
        var result: ArrayList<SubCue>? = null
        var i = lo - 1
        while (i >= 0 && sorted[i].startMs > positionMs - maxCueMs) {
            if (sorted[i].endMs > positionMs) (result ?: ArrayList<SubCue>().also { result = it }).add(sorted[i])
            i--
        }
        return result?.also { it.reverse() } ?: emptyList()
    }
}
