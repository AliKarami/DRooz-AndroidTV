package com.alikarami.drooz.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Typography
import androidx.tv.material3.darkColorScheme
import com.alikarami.drooz.R

object DroozColors {
    val Background = Color(0xFF0B0B0F)
    val Accent = Color(0xFFE50914)
    val ImdbBadge = Color(0xFFF5C518)
    val Watched = Color(0xFF46D369)
    val TextSecondary = Color(0xFF9EA2AD)
    val Surface = Color(0xFF1A1A21)
}

val Vazirmatn = FontFamily(
    Font(R.font.vazirmatn_regular, FontWeight.Normal),
    Font(R.font.vazirmatn_medium, FontWeight.Medium),
    Font(R.font.vazirmatn_bold, FontWeight.Bold),
    Font(R.font.vazirmatn_extrabold, FontWeight.ExtraBold),
)

private fun style(size: Int, weight: FontWeight) =
    TextStyle(fontFamily = Vazirmatn, fontSize = size.sp, fontWeight = weight)

private val DroozTypography = Typography(
    displayLarge = style(48, FontWeight.ExtraBold),
    displayMedium = style(40, FontWeight.ExtraBold),
    headlineLarge = style(32, FontWeight.Bold),
    headlineMedium = style(28, FontWeight.Bold),
    titleLarge = style(24, FontWeight.Bold),
    titleMedium = style(20, FontWeight.Medium),
    titleSmall = style(18, FontWeight.Medium),
    bodyLarge = style(18, FontWeight.Normal),
    bodyMedium = style(16, FontWeight.Normal),
    bodySmall = style(14, FontWeight.Normal),
    labelLarge = style(16, FontWeight.Medium),
    labelMedium = style(14, FontWeight.Medium),
    labelSmall = style(12, FontWeight.Medium),
)

/** Dark theme, Vazirmatn typography, whole app right-to-left. */
@Composable
fun DroozTheme(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        MaterialTheme(
            colorScheme = darkColorScheme(
                primary = DroozColors.Accent,
                background = DroozColors.Background,
                surface = DroozColors.Surface,
                onSurfaceVariant = DroozColors.TextSecondary,
            ),
            typography = DroozTypography,
            content = content,
        )
    }
}
