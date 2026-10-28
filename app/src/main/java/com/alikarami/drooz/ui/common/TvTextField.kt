package com.alikarami.drooz.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.alikarami.drooz.ui.theme.DroozCardShape
import com.alikarami.drooz.ui.theme.DroozColors
import com.alikarami.drooz.ui.theme.FocusScale
import com.alikarami.drooz.ui.theme.droozFocus

/**
 * A text field for the TV remote and the Google TV keyboard.
 *
 * Focus lands on a focusable *shell* that does not open the keyboard (a focused text field auto-opens the
 * IME, and then the D-pad belongs to the IME). OK on the shell switches to editing: the real text field
 * takes focus and the keyboard opens. While editing, Up/Down leave the field (text fields swallow them),
 * OK re-opens a dismissed keyboard, and Back — which reaches the field only once the keyboard is closed —
 * leaves edit mode and then runs [onBack] (null: stay on the field).
 *
 * [editing] is hoisted so a screen can chain fields (ImeAction.Next → the next field, still editing).
 * Use `autoCorrectEnabled = false` in [keyboardOptions]: autocorrect duplicates composing text.
 */
@Composable
fun TvTextField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    editing: Boolean,
    onEditingChange: (Boolean) -> Unit,
    shellRequester: FocusRequester,
    keyboardOptions: KeyboardOptions,
    onImeAction: () -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    textStyle: TextStyle = MaterialTheme.typography.titleMedium.copy(
        color = Color.White, fontSize = 22.sp,
        // Persian and English text both hug the start (right) edge.
        textAlign = TextAlign.Right, textDirection = TextDirection.Content,
    ),
    onFocusChanged: (Boolean) -> Unit = {},
    onBack: (() -> Unit)? = null,
    leading: @Composable RowScope.() -> Unit = {},
) {
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val inputRequester = remember { FocusRequester() }
    val currentEditing by rememberUpdatedState(editing)

    LaunchedEffect(editing) {
        if (editing) {
            inputRequester.requestFocusSoon()
            keyboard?.show()
        }
    }

    /** Leaves the text field in [dir] (or back to its shell if nothing is there) and closes the keyboard. */
    fun leave(dir: FocusDirection) {
        if (!focusManager.moveFocus(dir)) runCatching { shellRequester.requestFocus() }
        onEditingChange(false)
        keyboard?.hide()
    }

    Row(
        modifier
            .focusRequester(shellRequester)
            .onFocusChanged {
                onFocusChanged(it.hasFocus)
                if (!it.hasFocus && currentEditing) onEditingChange(false)
            }
            .droozFocus(FocusScale.FIELD)
            .background(DroozColors.Surface, DroozCardShape)
            .onPreviewKeyEvent { e ->
                if (editing || e.type != KeyEventType.KeyUp || !e.key.isOk()) return@onPreviewKeyEvent false
                onEditingChange(true)
                true
            }
            .focusable()
            .padding(horizontal = 18.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading()
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
            if (value.text.isEmpty() && placeholder != null) {
                Text(
                    placeholder, Modifier.fillMaxWidth(),
                    style = MaterialTheme.typography.titleMedium, color = DroozColors.TextSecondary, maxLines = 1,
                )
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = textStyle,
                visualTransformation = visualTransformation,
                cursorBrush = SolidColor(if (editing) DroozColors.Accent else Color.Transparent),
                keyboardOptions = keyboardOptions,
                keyboardActions = KeyboardActions(
                    onDone = { onImeAction() }, onGo = { onImeAction() }, onNext = { onImeAction() },
                    onSearch = { onImeAction() }, onSend = { onImeAction() },
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(inputRequester)
                    // Only focusable while editing, so focus landing on the field never opens the IME.
                    .focusProperties { canFocus = editing }
                    .onPreviewKeyEvent { e ->
                        // The text field would spend Back on dropping its own focus, an invisible step: leave
                        // edit mode instead and pass Back on.
                        if (e.key == Key.Back) {
                            if (e.type == KeyEventType.KeyUp) {
                                runCatching { shellRequester.requestFocus() }
                                onEditingChange(false)
                                onBack?.invoke()
                            }
                            return@onPreviewKeyEvent true
                        }
                        if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when {
                            e.key == Key.DirectionDown -> { leave(FocusDirection.Down); true }
                            e.key == Key.DirectionUp -> { leave(FocusDirection.Up); true }
                            // Keyboard dismissed with Back: OK brings it back. (Enter falls through: a hardware
                            // Enter runs the IME action.)
                            e.key == Key.DirectionCenter -> { keyboard?.show(); true }
                            else -> false
                        }
                    },
            )
        }
    }
}

private fun Key.isOk() = this == Key.DirectionCenter || this == Key.Enter || this == Key.NumPadEnter
