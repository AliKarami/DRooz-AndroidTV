package com.alikarami.drooz.player

import com.alikarami.drooz.site.model.Episode
import com.alikarami.drooz.site.model.Season
import kotlin.math.abs

/**
 * A snap that moves less than this is no move: the engine reports the sync sample it landed on in whole
 * ms, a hair off the site's keyframe value, and a 1 ms seek lands on the same sample again.
 */
const val SNAP_MIN_MOVE_SEC = 0.5

/**
 * Port of the site's seekControl plugin: snap a seek target to a keyframe, with edge guards. Unlike the
 * port, a seek always moves in its direction by at least [SNAP_MIN_MOVE_SEC]: with keyframes further
 * apart than the step the port could round back to the current one, and every press stayed put.
 */
fun snapToKeyframe(targetSec: Double, currentSec: Double, iframes: List<Double>, durationSec: Double): Double {
    val snapped = portSnap(targetSec, currentSec, iframes, durationSec)
    val ahead = currentSec + SNAP_MIN_MOVE_SEC
    val behind = currentSec - SNAP_MIN_MOVE_SEC
    return when {
        targetSec > currentSec && snapped < ahead ->
            (iframes.firstOrNull { it >= ahead } ?: targetSec).coerceIn(0.0, durationSec)
        targetSec < currentSec && snapped > behind ->
            (iframes.lastOrNull { it <= behind } ?: targetSec).coerceIn(0.0, durationSec)
        else -> snapped
    }
}

private fun portSnap(targetSec: Double, currentSec: Double, iframes: List<Double>, durationSec: Double): Double {
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
/** A resume starts this much before the saved position, so the scene picks up where the viewer left it. */
const val RESUME_REWIND_MS = 3_000L
const val FINISHED_FRACTION = 0.9

fun isFinished(positionMs: Long, durationMs: Long): Boolean =
    durationMs > 0 && positionMs >= durationMs * FINISHED_FRACTION

fun resumePositionMs(savedMs: Long, durationMs: Long, finished: Boolean): Long =
    if (savedMs > RESUME_MIN_MS && !finished && !isFinished(savedMs, durationMs)) savedMs else 0L

/** Where a resume from the watch store loads: [resumePositionMs] less [RESUME_REWIND_MS]; a start from 0 stays 0. */
fun resumeStartMs(savedMs: Long, durationMs: Long, finished: Boolean): Long =
    resumePositionMs(savedMs, durationMs, finished).let { if (it > 0) (it - RESUME_REWIND_MS).coerceAtLeast(0L) else 0L }

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

/** Hold-to-scrub speed (media ms per ms held) from each hold time on: 10 s/s, then 30, 60, 120. */
private val SCRUB_SPEEDS = listOf(0L to 10L, 2_000L to 30L, 4_000L to 60L, 6_000L to 120L)

/**
 * How far a key held for [heldMs] has moved the scrub head from where the hold began. A function of
 * time, not of key repeats: remotes repeat at different rates, and a repeat used to add a whole step.
 */
fun scrubOffsetMs(heldMs: Long): Long {
    if (heldMs <= 0) return 0L
    var offset = 0L
    SCRUB_SPEEDS.forEachIndexed { i, (from, speed) ->
        val until = SCRUB_SPEEDS.getOrNull(i + 1)?.first ?: Long.MAX_VALUE
        if (heldMs > from) offset += (minOf(heldMs, until) - from) * speed
    }
    return offset
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
