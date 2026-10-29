package com.alikarami.drooz.player

import com.alikarami.drooz.site.model.Episode
import com.alikarami.drooz.site.model.Season
import kotlin.math.abs

/** Port of the site's seekControl plugin: snap a seek target to a keyframe, with edge guards. */
fun snapToKeyframe(targetSec: Double, currentSec: Double, iframes: List<Double>, durationSec: Double): Double {
    if (iframes.isEmpty()) return targetSec.coerceIn(0.0, durationSec)
    var closest = 0
    var best = Double.MAX_VALUE
    iframes.forEachIndexed { i, f ->
        val d = abs(targetSec - f)
        if (d < best) { best = d; closest = i }
    }
    val x = iframes[closest]
    if (x != targetSec) {
        val seekGap = targetSec - currentSec
        val isUpper = x > currentSec
        val forward = seekGap > 0 && seekGap < 10
        val backward = seekGap < 0 && seekGap > -10
        val theGap = abs(x - currentSec)
        if (forward && (!isUpper || theGap < 3)) closest++
        else if (backward && (isUpper || theGap < 3)) closest--
        else if (!forward && !backward && x > targetSec) closest--
        closest = closest.coerceIn(0, iframes.lastIndex)
    }
    return iframes[closest].coerceIn(0.0, durationSec)
}

const val RESUME_MIN_MS = 30_000L
const val FINISHED_FRACTION = 0.9

fun isFinished(positionMs: Long, durationMs: Long): Boolean =
    durationMs > 0 && positionMs >= durationMs * FINISHED_FRACTION

fun resumePositionMs(savedMs: Long, durationMs: Long, finished: Boolean): Long =
    if (savedMs > RESUME_MIN_MS && !finished && !isFinished(savedMs, durationMs)) savedMs else 0L

const val UP_NEXT_LEAD_MS = 40_000L
const val UP_NEXT_COUNTDOWN_S = 8
const val UP_NEXT_MIN_DURATION_MS = 120_000L

fun upNextDue(positionMs: Long, durationMs: Long): Boolean =
    durationMs >= UP_NEXT_MIN_DURATION_MS && durationMs - positionMs <= UP_NEXT_LEAD_MS

fun nextEpisode(seasons: List<Season>, streamId: String): Episode? {
    val ordered = seasons.sortedBy { it.number }.flatMap { s -> s.episodes.sortedBy { it.number } }
    val i = ordered.indexOfFirst { it.streamId == streamId }
    return if (i < 0) null else ordered.getOrNull(i + 1)
}

fun scrubStepMs(repeatCount: Int): Long = when {
    repeatCount >= 10 -> 60_000L
    repeatCount >= 5 -> 30_000L
    else -> 10_000L
}

data class QualityOption(val height: Int, val label: String)

/** One video rendition as the engine reports it; [bitrateKbps] null when the playlist gives none. */
data class VariantInfo(val height: Int, val bitrateKbps: Int?)

/** A site label within this fraction of a variant's bitrate is its label (the site rounds its keys). */
const val QUALITY_BITRATE_TOLERANCE = 0.15

/**
 * One option per height (the highest-bitrate variant of each), highest first. The site keys its labels
 * by bitrate in kbps: exact key, else the nearest key within [QUALITY_BITRATE_TOLERANCE]; only an unknown
 * bitrate or no match falls back to a label starting with the height, then to «{h}p».
 */
fun qualityOptions(variants: List<VariantInfo>, siteLabels: Map<Int, String>): List<QualityOption> =
    variants.groupBy { it.height }
        .map { (_, same) -> same.maxBy { it.bitrateKbps ?: -1 } }
        .sortedByDescending { it.height }
        .map { v ->
            val label = v.bitrateKbps?.let { labelByBitrate(it, siteLabels) }
                ?: siteLabels.values.firstOrNull { it.trimStart().takeWhile(Char::isDigit).toIntOrNull() == v.height }
            QualityOption(v.height, label ?: "${v.height}p")
        }

private fun labelByBitrate(kbps: Int, siteLabels: Map<Int, String>): String? {
    siteLabels[kbps]?.let { return it }
    val nearest = siteLabels.keys.minByOrNull { abs(it - kbps) } ?: return null
    return if (abs(nearest - kbps) <= kbps * QUALITY_BITRATE_TOLERANCE) siteLabels[nearest] else null
}
