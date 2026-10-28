package com.alikarami.drooz.ui.login

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.alikarami.drooz.R
import com.alikarami.drooz.ui.common.BusySpinner
import com.alikarami.drooz.ui.common.TvTextField
import com.alikarami.drooz.ui.common.requestFocusSoon
import com.alikarami.drooz.ui.theme.DroozCardShape
import com.alikarami.drooz.ui.theme.DroozColors
import com.alikarami.drooz.ui.theme.FocusScale
import com.alikarami.drooz.ui.theme.droozFocus
import com.alikarami.drooz.ui.theme.droozSurfaceScale

private val FieldHeight = 56.dp
private val FormWidth = 460.dp
private val ErrorColor = Color(0xFFFF6B6B)
/** Where the Google TV keyboard's top edge sits (it reports no insets). */
private const val KeyboardTopFraction = 0.55f

private enum class LoginField { Username, Password, Mobile }

@Composable
fun LoginScreen(vm: LoginViewModel, onLoggedIn: () -> Unit) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    val loggedIn by rememberUpdatedState(onLoggedIn)
    LaunchedEffect(vm) { vm.loggedIn.collect { loggedIn() } }
    LoginContent(state, vm::onUsername, vm::onPassword, vm::onMobile, vm::submit)
}

/**
 * Debug-only preview (launch extra `debug_preview_login`): the real form with local state; submit only
 * shows a toast, so the device's session is never touched. [withMobile] shows the mobile step.
 */
@Composable
fun LoginPreview(withMobile: Boolean) {
    val context = LocalContext.current
    var state by remember { mutableStateOf(LoginUiState(mobileHint = if (withMobile) "09xx***1234" else null)) }
    LoginContent(
        state,
        onUsername = { state = state.copy(username = it) },
        onPassword = { state = state.copy(password = it) },
        onMobile = { state = state.copy(mobile = it) },
        onSubmit = { Toast.makeText(context, "پیش‌نمایش: ورود ارسال نشد", Toast.LENGTH_SHORT).show() },
    )
}

/**
 * Login form for the remote: username, password, then the mobile number once the site asks for it.
 * Fields are [TvTextField]s (focus never opens the keyboard by itself; OK does). The keyboard's Next
 * moves straight on to the next field, still typing; Done on the last field submits.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LoginContent(
    state: LoginUiState,
    onUsername: (String) -> Unit,
    onPassword: (String) -> Unit,
    onMobile: (String) -> Unit,
    onSubmit: () -> Unit,
) {
    val keyboard = LocalSoftwareKeyboardController.current
    // Local values keep the cursor / IME composition in sync; the view model holds the strings. They are
    // seeded once and never written back from [state]: a write-back races the IME and drops characters.
    var user by remember { mutableStateOf(TextFieldValue(state.username, TextRange(state.username.length))) }
    var pass by remember { mutableStateOf(TextFieldValue(state.password, TextRange(state.password.length))) }
    var mobile by remember { mutableStateOf(TextFieldValue(state.mobile, TextRange(state.mobile.length))) }
    var editing by remember { mutableStateOf<LoginField?>(null) }
    // Bottom edge of each field inside the form (px, unaffected by the lift), to lift the edited one above
    // the keyboard.
    val fieldBottoms = remember { mutableStateMapOf<LoginField, Float>() }
    val form = remember { arrayOfNulls<LayoutCoordinates>(1) }
    fun track(f: LoginField) = Modifier.onGloballyPositioned { c ->
        form[0]?.takeIf { it.isAttached }?.let { fieldBottoms[f] = it.localPositionOf(c, Offset.Zero).y + c.size.height }
    }
    val userRequester = remember { FocusRequester() }
    val passRequester = remember { FocusRequester() }
    val mobileRequester = remember { FocusRequester() }
    val submitRequester = remember { FocusRequester() }
    val askMobile = state.mobileHint != null

    fun editingChange(field: LoginField): (Boolean) -> Unit = { on ->
        editing = if (on) field else if (editing == field) null else editing
    }

    fun submit() {
        editing = null
        keyboard?.hide()
        runCatching { submitRequester.requestFocus() }
        onSubmit()
    }

    LaunchedEffect(Unit) { if (!askMobile) userRequester.requestFocusSoon() }
    // The site asked for the mobile: focus that field (OK opens the keyboard; opening it right away
    // would cover the hint that explains why the form grew).
    LaunchedEffect(askMobile) {
        if (askMobile) mobileRequester.requestFocusSoon()
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(DroozColors.Background)
            .background(
                Brush.radialGradient(
                    listOf(DroozColors.Accent.copy(alpha = 0.22f), Color.Transparent),
                    radius = 1400f,
                ),
            ),
    ) {
      // The Google TV keyboard floats over the lower half of the screen without reporting insets: while a
      // field is being edited, slide the form up so that field sits above it.
      BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val density = LocalDensity.current
        val screenHeight = with(density) { maxHeight.toPx() }
        // Prefer the IME's own inset and visibility; the Google TV keyboard may report neither, so the 55%
        // keyboard top is only a fallback.
        val imeBottom = WindowInsets.ime.getBottom(density).toFloat()
        val imeVisible = WindowInsets.isImeVisible
        // Once the IME has been seen reporting visibility, trust it: closing the keyboard with Back (the IME
        // eats that press, editing stays on) drops the lift. Until then (an IME that reports nothing), lift
        // whenever a field is being edited.
        var imeReports by remember { mutableStateOf(false) }
        if (imeVisible) imeReports = true
        val keyboardUp = editing != null && (imeVisible || !imeReports)
        val keyboardTop = (if (imeBottom > 0f) screenHeight - imeBottom else screenHeight * KeyboardTopFraction) -
            with(density) { 16.dp.toPx() }
        var formHeight by remember { mutableStateOf(0f) }
        // The form is centred: its top is half the spare height down.
        val fieldBottom = editing?.takeIf { keyboardUp }?.let { fieldBottoms[it] }?.plus((screenHeight - formHeight) / 2)
        val lift by animateFloatAsState(
            if (fieldBottom != null) (keyboardTop - fieldBottom).coerceAtMost(0f) else 0f,
            tween(250), label = "keyboardLift",
        )
        Column(
            Modifier.width(FormWidth)
                .graphicsLayer { translationY = lift }
                .onGloballyPositioned { form[0] = it; formHeight = it.size.height.toFloat() },
            verticalArrangement = Arrangement.spacedBy(if (askMobile) 6.dp else 8.dp),
        ) {
            // Smaller brand on the mobile step: the taller form must stay inside the 5% overscan-safe area.
            Text(
                stringResource(R.string.app_name),
                style = MaterialTheme.typography.displayMedium.copy(fontSize = if (askMobile) 30.sp else 40.sp),
                color = DroozColors.Accent, fontWeight = FontWeight.ExtraBold,
            )
            Text(
                stringResource(R.string.login_heading), Modifier.padding(bottom = if (askMobile) 2.dp else 8.dp),
                style = MaterialTheme.typography.titleLarge, color = Color.White,
            )

            Label(stringResource(R.string.login_username))
            Field(
                user, { user = it; onUsername(it.text) }, editing == LoginField.Username,
                editingChange(LoginField.Username), userRequester,
                KeyboardOptions(keyboardType = KeyboardType.Email, autoCorrectEnabled = false, imeAction = ImeAction.Next),
                onImeAction = { editing = LoginField.Password },
                track(LoginField.Username),
            )
            Label(stringResource(R.string.login_password))
            Field(
                pass, { pass = it; onPassword(it.text) }, editing == LoginField.Password,
                editingChange(LoginField.Password), passRequester,
                KeyboardOptions(
                    keyboardType = KeyboardType.Password, autoCorrectEnabled = false,
                    imeAction = if (askMobile) ImeAction.Next else ImeAction.Done,
                ),
                onImeAction = { if (askMobile) editing = LoginField.Mobile else submit() },
                track(LoginField.Password),
                visualTransformation = PasswordVisualTransformation(),
                // Dots are direction-neutral: lay them out LTR so the cursor follows the last one.
                ltr = true,
            )
            AnimatedVisibility(askMobile, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    // The site's request doubles as the field's label.
                    Text(
                        stringResource(R.string.login_mobile_hint),
                        style = MaterialTheme.typography.labelLarge, color = DroozColors.ImdbBadge,
                    )
                    Field(
                        mobile, { mobile = it; onMobile(it.text) }, editing == LoginField.Mobile,
                        editingChange(LoginField.Mobile), mobileRequester,
                        KeyboardOptions(keyboardType = KeyboardType.Phone, autoCorrectEnabled = false, imeAction = ImeAction.Done),
                        onImeAction = ::submit,
                        track(LoginField.Mobile),
                        // The site's masked number (e.g. 09xx***1234) as the placeholder.
                        placeholder = state.mobileHint,
                        ltr = true,
                    )
                }
            }

            Box(Modifier.fillMaxWidth().height(26.dp), contentAlignment = Alignment.CenterStart) {
                state.error?.let { Text(it, style = MaterialTheme.typography.bodyLarge, color = ErrorColor, maxLines = 1) }
            }
            SubmitButton(state.busy, ::submit, Modifier.fillMaxWidth().focusRequester(submitRequester))
            // The tip makes room for the mobile step (the form must fit the screen).
            if (!askMobile) {
                Text(
                    stringResource(R.string.login_tip), Modifier.fillMaxWidth().padding(top = 8.dp),
                    style = MaterialTheme.typography.bodyMedium, color = DroozColors.TextSecondary,
                )
            }
        }
      }
    }
}

@Composable
private fun Label(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = DroozColors.TextSecondary)
}

@Composable
private fun Field(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    editing: Boolean,
    onEditingChange: (Boolean) -> Unit,
    requester: FocusRequester,
    keyboardOptions: KeyboardOptions,
    onImeAction: () -> Unit,
    modifier: Modifier,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    placeholder: String? = null,
    ltr: Boolean = false,
) {
    TvTextField(
        value = value,
        onValueChange = onValueChange,
        editing = editing,
        onEditingChange = onEditingChange,
        shellRequester = requester,
        keyboardOptions = keyboardOptions,
        onImeAction = onImeAction,
        modifier = modifier.fillMaxWidth().height(FieldHeight),
        placeholder = placeholder,
        visualTransformation = visualTransformation,
        textStyle = MaterialTheme.typography.titleMedium.copy(
            color = Color.White, fontSize = 22.sp, textAlign = TextAlign.Right,
            textDirection = if (ltr) TextDirection.Ltr else TextDirection.Content,
        ),
    )
}

@Composable
private fun SubmitButton(busy: Boolean, onClick: () -> Unit, modifier: Modifier) {
    Surface(
        onClick = onClick,
        modifier = modifier.height(FieldHeight).droozFocus(FocusScale.FIELD),
        shape = ClickableSurfaceDefaults.shape(DroozCardShape),
        scale = droozSurfaceScale,
        colors = ClickableSurfaceDefaults.colors(
            containerColor = DroozColors.Accent,
            contentColor = Color.White,
            focusedContainerColor = DroozColors.Accent,
            focusedContentColor = Color.White,
            pressedContainerColor = DroozColors.Accent.copy(alpha = 0.8f),
            pressedContentColor = Color.White,
        ),
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                stringResource(R.string.login_submit), Modifier.alpha(if (busy) 0f else 1f),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            )
            if (busy) BusySpinner(size = 24.dp, stroke = 3.dp)
        }
    }
}
