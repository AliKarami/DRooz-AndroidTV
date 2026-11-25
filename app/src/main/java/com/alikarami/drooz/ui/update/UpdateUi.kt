package com.alikarami.drooz.ui.update

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.alikarami.drooz.R
import com.alikarami.drooz.ui.common.PillButton
import com.alikarami.drooz.ui.common.UiStrings
import com.alikarami.drooz.ui.common.requestFocusSoon
import com.alikarami.drooz.ui.theme.DroozColors
import com.alikarami.drooz.update.UpdateState
import kotlin.math.roundToInt

/** What the update UI can ask for. */
class UpdateActions(
    val check: () -> Unit,
    val install: () -> Unit,
    val openPermission: () -> Unit,
)

/** The status line for [state], or null when there is nothing to say. */
@Composable
fun updateStatusText(state: UpdateState): String? = when (state) {
    UpdateState.Idle -> null
    UpdateState.Checking -> stringResource(R.string.update_checking)
    UpdateState.UpToDate -> stringResource(R.string.update_up_to_date)
    is UpdateState.Available -> stringResource(R.string.update_available, state.release.version.toString())
    is UpdateState.Downloading ->
        stringResource(R.string.update_downloading, UiStrings.percent((state.progress * 100).roundToInt()))
    is UpdateState.NeedsPermission -> stringResource(R.string.update_permission)
    is UpdateState.Installing -> stringResource(R.string.update_installing)
    is UpdateState.Failed -> stringResource(
        when (state.step) {
            UpdateState.Step.CHECK -> R.string.update_failed_check
            UpdateState.Step.DOWNLOAD -> R.string.update_failed_download
            UpdateState.Step.INSTALL -> R.string.update_failed_install
        },
    )
}

/** The one action button for [state] (label to action), or null while there is nothing to press. */
@Composable
fun updateAction(state: UpdateState, actions: UpdateActions): Pair<String, () -> Unit>? = when (state) {
    UpdateState.Idle, UpdateState.UpToDate -> stringResource(R.string.update_check) to actions.check
    is UpdateState.Failed ->
        if (state.release == null) stringResource(R.string.retry) to actions.check
        else stringResource(R.string.retry) to actions.install
    is UpdateState.Available -> stringResource(R.string.update_install) to actions.install
    is UpdateState.NeedsPermission -> stringResource(R.string.update_open_settings) to actions.openPermission
    is UpdateState.Installing -> stringResource(R.string.retry) to actions.install
    UpdateState.Checking, is UpdateState.Downloading -> null
}

/**
 * Home's «نسخه جدید» panel, in its own window so Home's focus moves underneath can't take focus from it.
 * «بعداً» (or Back) closes it; a download carries on and Settings shows where it is.
 */
@Composable
fun UpdateDialog(state: UpdateState, actions: UpdateActions, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val primary = remember { FocusRequester() }
        val close = remember { FocusRequester() }
        val action = updateAction(state, actions)
        // The buttons change with the state: put focus back on one that is there.
        LaunchedEffect(state::class, action != null) {
            (if (action != null) primary else close).requestFocusSoon()
        }
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.7f)), contentAlignment = Alignment.Center) {
            Column(
                Modifier
                    .widthIn(min = 420.dp, max = 640.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(DroozColors.Surface)
                    .focusProperties { onExit = { cancelFocusChange() } }
                    .focusGroup()
                    .padding(horizontal = 32.dp, vertical = 28.dp),
            ) {
                val release = releaseOf(state)
                if (release != null) {
                    Text(
                        stringResource(R.string.update_available, release.version.toString()),
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold), color = Color.White,
                    )
                    if (release.notes.isNotBlank()) {
                        // The notes are English: laid out left-to-right.
                        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                            Text(
                                release.notes, Modifier.padding(top = 14.dp),
                                style = MaterialTheme.typography.bodySmall, color = DroozColors.TextSecondary,
                                maxLines = 8, overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
                if (state !is UpdateState.Available) {
                    updateStatusText(state)?.let {
                        Text(
                            it, Modifier.padding(top = 14.dp),
                            style = MaterialTheme.typography.bodyMedium, color = Color.White,
                        )
                    }
                }
                Row(Modifier.padding(top = 24.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    action?.let { (label, onClick) -> PillButton(label, onClick, Modifier.focusRequester(primary)) }
                    PillButton(
                        stringResource(if (state is UpdateState.Available) R.string.update_later else R.string.update_close),
                        onDismiss, Modifier.focusRequester(close),
                    )
                }
            }
        }
    }
}

private fun releaseOf(s: UpdateState) = when (s) {
    is UpdateState.Available -> s.release
    is UpdateState.Downloading -> s.release
    is UpdateState.NeedsPermission -> s.release
    is UpdateState.Installing -> s.release
    is UpdateState.Failed -> s.release
    else -> null
}
