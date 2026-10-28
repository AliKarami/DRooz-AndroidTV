package com.alikarami.drooz.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.alikarami.drooz.R
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.alikarami.drooz.ui.theme.DroozColors
import com.alikarami.drooz.ui.theme.droozFocus

private val CardShape = RoundedCornerShape(10.dp)

/** Poster (2:3) with the title underneath. */
@Composable
fun PosterCard(title: String, posterUrl: String?, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.width(160.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Surface(
            onClick = onClick,
            modifier = Modifier.droozFocus(1.08f).fillMaxWidth().height(240.dp),
            shape = ClickableSurfaceDefaults.shape(CardShape),
        ) {
            DroozImage(posterUrl, ImageSizes.POSTER_W, ImageSizes.POSTER_H, Modifier.fillMaxWidth().fillMaxHeight())
        }
        TitleText(title, MaterialTheme.typography.bodySmall, color = Color.White, maxLines = 2)
    }
}

/** 16:9 card with title/subtitle and an optional red progress bar along the bottom. */
@Composable
fun LandscapeCard(
    title: String,
    subtitle: String?,
    imageUrl: String?,
    progress: Float?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.width(220.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Surface(
            onClick = onClick,
            modifier = Modifier.droozFocus(1.08f).fillMaxWidth().height(124.dp),
            shape = ClickableSurfaceDefaults.shape(CardShape),
        ) {
            Box(Modifier.fillMaxWidth().fillMaxHeight()) {
                DroozImage(imageUrl, ImageSizes.CARD_W, ImageSizes.CARD_H, Modifier.fillMaxWidth().fillMaxHeight())
                if (progress != null) ProgressBar(progress, Modifier.align(Alignment.BottomStart))
            }
        }
        TitleText(title, MaterialTheme.typography.bodySmall, color = Color.White, maxLines = 1)
        if (subtitle != null) {
            Text(subtitle, style = MaterialTheme.typography.labelSmall, color = DroozColors.TextSecondary, maxLines = 1)
        }
    }
}

/** Episode number tile: Persian digits, a green check when watched, optional progress bar. */
@Composable
fun EpisodeTile(
    number: Int,
    minutes: Int?,
    watched: Boolean,
    progress: Float?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        modifier = modifier.droozFocus(1.1f).size(width = 96.dp, height = 72.dp),
        shape = ClickableSurfaceDefaults.shape(CardShape),
    ) {
        Box(Modifier.fillMaxWidth().fillMaxHeight().padding(8.dp)) {
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(number.toString().toPersianDigits(), style = MaterialTheme.typography.titleMedium, color = Color.White)
                if (minutes != null) {
                    Text(
                        stringResource(R.string.minutes_short, minutes.toString().toPersianDigits()),
                        style = MaterialTheme.typography.labelSmall,
                        color = DroozColors.TextSecondary,
                    )
                }
            }
            if (watched) {
                Text("✓", Modifier.align(Alignment.TopStart), color = DroozColors.Watched,
                    style = MaterialTheme.typography.labelMedium)
            }
            if (progress != null && !watched) ProgressBar(progress, Modifier.align(Alignment.BottomStart))
        }
    }
}

@Composable
private fun ProgressBar(fraction: Float, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(4.dp).background(Color.Black.copy(alpha = 0.5f))) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(fraction.coerceIn(0f, 1f)).background(DroozColors.Accent))
    }
}
