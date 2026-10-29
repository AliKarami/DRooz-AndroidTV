package com.alikarami.drooz.player.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/** Control-bar glyphs. Tinted by the caller (white idle, black focused). */
internal object PlayerIcons {
    val Replay = icon("Replay", REPLAY)
    val Subtitles = icon("Subtitles", CLOSED_CAPTION)
    val Quality = icon("Quality", HD)
    val Speed = icon("Speed", SPEED)
    val Episodes = icon("Episodes", VIDEO_LIBRARY)
    val Next = icon("Next", SKIP_NEXT)

    private fun icon(name: String, pathData: String): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
            .addPath(addPathNodes(pathData), fill = SolidColor(Color.White))
            .build()
}

// 24x24 glyph outlines (Material Design icon geometry, Apache 2.0).
private const val REPLAY = "M12,5V1L7,6l5,5V7c3.31,0 6,2.69 6,6s-2.69,6 -6,6 -6,-2.69 -6,-6H4c0,4.42 3.58,8 8,8" +
    "s8,-3.58 8,-8 -3.58,-8 -8,-8z"
private const val CLOSED_CAPTION = "M19,4H5c-1.11,0 -2,0.9 -2,2v12c0,1.1 0.89,2 2,2h14c1.1,0 2,-0.9 2,-2V6" +
    "c0,-1.1 -0.9,-2 -2,-2zM11,11H9.5v-0.5h-2v3h2V13H11v1c0,0.55 -0.45,1 -1,1H7c-0.55,0 -1,-0.45 -1,-1v-4" +
    "c0,-0.55 0.45,-1 1,-1h3c0.55,0 1,0.45 1,1v1zM18,11h-1.5v-0.5h-2v3h2V13H18v1c0,0.55 -0.45,1 -1,1h-3" +
    "c-0.55,0 -1,-0.45 -1,-1v-4c0,-0.55 0.45,-1 1,-1h3c0.55,0 1,0.45 1,1v1z"
private const val HD = "M19,3H5c-1.11,0 -2,0.9 -2,2v14c0,1.1 0.89,2 2,2h14c1.1,0 2,-0.9 2,-2V5c0,-1.1 -0.9,-2 -2,-2z" +
    "M11,15H9.5v-2h-2v2H6V9h1.5v2.5h2V9H11v6zM13,9h4c0.55,0 1,0.45 1,1v4c0,0.55 -0.45,1 -1,1h-4V9zM14.5,13.5h2v-3h-2v3z"
private const val SPEED = "M20.38,8.57l-1.23,1.85a8,8 0,0 1,-0.22 7.58L5.07,18A8,8 0,0 1,15.58 6.85l1.85,-1.23" +
    "A10,10 0,0 0,3.35 19a2,2 0,0 0,1.72 1h13.85a2,2 0,0 0,1.74 -1,10 10,0 0,0 -0.27,-10.44z" +
    "M10.59,15.41a2,2 0,0 0,2.83 0l5.66,-8.49 -8.49,5.66a2,2 0,0 0,0 2.83z"
private const val VIDEO_LIBRARY = "M4,6H2v14c0,1.1 0.9,2 2,2h14v-2H4V6zM20,2H8c-1.1,0 -2,0.9 -2,2v12c0,1.1 0.9,2 2,2h12" +
    "c1.1,0 2,-0.9 2,-2V4c0,-1.1 -0.9,-2 -2,-2zM12,14.5v-9l6,4.5 -6,4.5z"
private const val SKIP_NEXT = "M6,18l8.5,-6L6,6v12zM16,6v12h2V6h-2z"
