package com.alikarami.drooz.site.parse

import com.alikarami.drooz.site.SiteConfig
import com.alikarami.drooz.site.SiteException
import com.alikarami.drooz.site.model.StreamSession
import com.alikarami.drooz.site.model.SubtitleTrack

/** Reads the JW Player setup script of a `/stream/{streamId}/{postId}/` page. */
object StreamPageParser {
    private val MASTER = Regex("""file:\s*'(/json/m3u8/[^']+)'""")
    private val TRACK = Regex("""kind:\s*'captions',\s*file:\s*"([^"]+)",\s*label:\s*'([^']+)'""")
    private val QUALITY_BLOCK = Regex("""qualityLabels:\s*\{([^}]*)\}""")
    private val QUALITY = Regex("""'(\d+)'\s*:\s*'([^']*)'""")
    private val GAINS = Regex("""Gains\s*=\s*(\[\[.*?\]\]);""")
    private val PAIR = Regex("""\[([\d.]+),([\d.]+)\]""")
    private val MIN_GAIN = Regex("""minGain\s*=\s*([\d.]+);""")
    private val MAX_GAIN = Regex("""maxGain\s*=\s*([\d.]+);""")
    private val IFRAMES = Regex("""IFrames\[(\d+)\]\s*=\s*\[([^\]]*)\];""")
    private val TITLE = Regex("""title:\s*"([^"]*)"""")
    private val IMAGE = Regex("""image:\s*'([^']+)'""")

    fun parse(html: String, streamId: String): StreamSession {
        val master = MASTER.find(html)?.groupValues?.get(1) ?: throw SiteException.ParseError("m3u8")
        return StreamSession(
            title = TITLE.find(html)?.groupValues?.get(1).orEmpty(),
            masterUrl = absolute(master),
            backdropPath = IMAGE.find(html)?.groupValues?.get(1),
            subtitles = TRACK.findAll(html).map { SubtitleTrack(it.groupValues[2], absolute(it.groupValues[1])) }.toList(),
            qualityLabels = QUALITY_BLOCK.find(html)?.groupValues?.get(1)
                ?.let { block -> QUALITY.findAll(block).associate { it.groupValues[1].toInt() to it.groupValues[2] } }
                .orEmpty(),
            gains = GAINS.find(html)?.groupValues?.get(1)
                ?.let { block -> PAIR.findAll(block).map { it.groupValues[1].toDouble() to it.groupValues[2].toDouble() }.toList() }
                .orEmpty(),
            minGain = MIN_GAIN.find(html)?.groupValues?.get(1)?.toDoubleOrNull(),
            maxGain = MAX_GAIN.find(html)?.groupValues?.get(1)?.toDoubleOrNull(),
            iframes = IFRAMES.findAll(html)
                .map { m -> m.groupValues[1].toInt() to m.groupValues[2].split(',').mapNotNull { it.trim().toDoubleOrNull() } }
                .sortedBy { it.first }
                .map { it.second }
                .toList(),
            streamId = streamId,
        )
    }

    private fun absolute(path: String) = if (path.startsWith("/")) SiteConfig.BASE_URL + path else path
}
