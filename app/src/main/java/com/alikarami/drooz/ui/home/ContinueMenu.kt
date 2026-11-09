package com.alikarami.drooz.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.alikarami.drooz.R
import com.alikarami.drooz.ui.common.PillShape
import com.alikarami.drooz.ui.common.TitleText
import com.alikarami.drooz.ui.common.requestFocusSoon
import com.alikarami.drooz.ui.theme.DroozColors

/**
 * Long-press menu of a «ادامه تماشا» card: «جزئیات» (focused first) and «حذف از ادامه تماشا». A dialog
 * window, so the home screen keeps its focused card underneath: Back (or «جزئیات») leaves focus on it, and
 * a removal hands it to the card now in its place through the row's usual removed-card path.
 */
@Composable
fun ContinueMenu(title: String, onDetails: () -> Unit, onRemove: () -> Unit, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val first = remember { FocusRequester() }
        LaunchedEffect(Unit) { first.requestFocusSoon() }
        // The OK press that opened the menu is still held: its repeats and its release reach this window
        // and must not pick «جزئیات». Only an OK pressed down here counts.
        val pressedHere = remember { booleanArrayOf(false) }
        Column(
            Modifier
                .width(380.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(DroozColors.Surface)
                .onPreviewKeyEvent { e ->
                    if (e.key != Key.DirectionCenter && e.key != Key.Enter && e.key != Key.NumPadEnter) {
                        return@onPreviewKeyEvent false
                    }
                    if (e.type == KeyEventType.KeyDown && e.nativeKeyEvent.repeatCount == 0) pressedHere[0] = true
                    !pressedHere[0]
                }
                .focusGroup()
                .padding(horizontal = 20.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TitleText(
                title, MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, textAlign = TextAlign.Right),
                Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, bottom = 8.dp), color = Color.White, maxLines = 2,
            )
            MenuItem(stringResource(R.string.cw_menu_details), onDetails, Modifier.focusRequester(first))
            MenuItem(stringResource(R.string.cw_menu_remove), onRemove)
        }
    }
}

/** A full-width row of the menu; white with black text when focused, like the title page's buttons. */
@Composable
private fun MenuItem(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = ClickableSurfaceDefaults.shape(PillShape),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.02f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.White.copy(alpha = 0.08f),
            contentColor = Color.White,
            focusedContainerColor = Color.White,
            focusedContentColor = Color.Black,
            pressedContainerColor = Color.White.copy(alpha = 0.85f),
            pressedContentColor = Color.Black,
        ),
    ) {
        Text(
            text, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
            maxLines = 1,
        )
    }
}
