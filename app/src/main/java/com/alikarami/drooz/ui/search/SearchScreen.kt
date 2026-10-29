package com.alikarami.drooz.ui.search

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.speech.RecognizerIntent
import android.widget.Toast
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.alikarami.drooz.R
import com.alikarami.drooz.site.model.TitleRef
import com.alikarami.drooz.ui.common.ErrorState
import com.alikarami.drooz.ui.common.TvTextField
import com.alikarami.drooz.ui.common.requestFocusSoon
import com.alikarami.drooz.ui.home.HeroBackdrop
import com.alikarami.drooz.ui.home.HeroInfo
import com.alikarami.drooz.ui.home.RailItem
import com.alikarami.drooz.ui.list.GridContentStart
import com.alikarami.drooz.ui.list.PosterGrid
import com.alikarami.drooz.ui.list.ProvideGridPivot
import com.alikarami.drooz.ui.list.SkeletonGrid
import com.alikarami.drooz.ui.list.gridCellWidth
import com.alikarami.drooz.ui.theme.DroozCardShape
import com.alikarami.drooz.ui.theme.DroozColors
import com.alikarami.drooz.ui.theme.FocusScale
import com.alikarami.drooz.ui.theme.droozFocus
import com.alikarami.drooz.ui.theme.droozSurfaceColors
import com.alikarami.drooz.ui.theme.droozSurfaceScale

/** The results grid starts at this fraction of the screen height (search row + compact hero above it). */
private const val GridFraction = 0.44f
private val FieldHeight = 56.dp

/**
 * Search: a field on the system TV keyboard plus a voice button, results in the list poster grid under a
 * compact focus-following hero.
 *
 * Focus starts on the field *without* opening the keyboard (a focused text field auto-opens the IME, and
 * then the D-pad belongs to the IME): the field is a focusable shell, and OK turns on the real text field.
 * Typing is the primary path and voice is one press to the left, so landing on the field reads as "type
 * here" while still letting the user reach the mic or the results without an IME in the way.
 */
@Composable
fun SearchScreen(vm: SearchViewModel, onOpen: (TitleRef) -> Unit) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val keyboard = LocalSoftwareKeyboardController.current
    val backDispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher

    // Local field value (cursor + IME composition stay in sync without a state-flow round trip); saveable so
    // Back from a title page shows the same text.
    var field by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(state.query, TextRange(state.query.length)))
    }
    var editing by remember { mutableStateOf(false) }
    val fieldRequester = remember { FocusRequester() }
    val firstCard = remember { FocusRequester() }
    // Set by the IME's Search key, voice and retry: focus the first result once the answer lands.
    var jumpToResults by remember { mutableStateOf(false) }
    // Set by the error's retry button: if the retry fails too, focus goes back to the (new) retry button.
    var refocusRetry by remember { mutableStateOf(false) }
    val retryRequester = remember { FocusRequester() }
    // Coming back from a title page with focus in the results: that grid restores its focused card.
    var restoreKey by remember { mutableStateOf(if (vm.focusInResults && state.items.isNotEmpty()) state.searchedFor else null) }

    LaunchedEffect(Unit) {
        if (restoreKey == null) fieldRequester.requestFocusSoon()
    }
    // Keyed on the decision, not the whole state: hero updates while focus moves must not restart it.
    val jump = state.resultsJump()
    LaunchedEffect(jumpToResults, jump) {
        if (!jumpToResults) return@LaunchedEffect
        when (jump) {
            ResultsJump.Wait -> Unit
            ResultsJump.Focus -> { firstCard.requestFocusSoon(); jumpToResults = false }
            ResultsJump.Drop -> jumpToResults = false
        }
    }
    // A retry that ends without results: back on the retry button if it failed again, else on the field
    // (the button that had focus is gone, and focus must not be left nowhere).
    val retryOutcome = when {
        state.loading -> null
        state.error != null -> 1
        state.items.isEmpty() -> 2
        else -> 0
    }
    LaunchedEffect(refocusRetry, retryOutcome) {
        if (!refocusRetry || retryOutcome == null) return@LaunchedEffect
        refocusRetry = false
        when (retryOutcome) {
            1 -> retryRequester.requestFocusSoon()
            2 -> fieldRequester.requestFocusSoon()
        }
    }

    val voiceIntent = remember { voiceIntent(context.getString(R.string.search_voice_prompt)) }
    val voiceAvailable = remember { context.packageManager.queryIntentActivities(voiceIntent, 0).isNotEmpty() }
    val voice = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val text = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.trim()
        if (result.resultCode == Activity.RESULT_OK && !text.isNullOrEmpty()) {
            field = TextFieldValue(text, TextRange(text.length))
            vm.onVoiceResult(text)
            jumpToResults = true
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(DroozColors.Background)) {
        val gridTop = maxHeight * GridFraction
        val cellWidth = gridCellWidth(maxWidth)
        // The backdrop keeps its last image when the hero goes away; fade it out instead, so a cleared or
        // changed query never sits on an unrelated title's backdrop.
        val backdropAlpha by animateFloatAsState(if (state.hero != null) 1f else 0f, tween(300), label = "backdrop")
        HeroBackdrop(state.hero, Modifier.fillMaxWidth().height(maxHeight * 0.55f).alpha(backdropAlpha))
        Column(
            Modifier.align(Alignment.TopStart).padding(start = GridContentStart, top = 24.dp).width(600.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                // The shell takes focus without opening the keyboard; OK switches to editing (see TvTextField).
                TvTextField(
                    value = field,
                    onValueChange = { v ->
                        val changed = v.text != field.text
                        field = v
                        if (changed) {
                            // Typing again cancels a pending jump: results must never pull focus mid-word.
                            jumpToResults = false
                            vm.onQueryChange(v.text)
                        }
                    },
                    editing = editing,
                    onEditingChange = { editing = it },
                    shellRequester = fieldRequester,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Text,
                        autoCorrectEnabled = false,
                        imeAction = ImeAction.Search,
                    ),
                    onImeAction = {
                        vm.submit()
                        keyboard?.hide()
                        jumpToResults = true
                    },
                    modifier = Modifier.weight(1f).height(FieldHeight),
                    placeholder = stringResource(R.string.search_placeholder),
                    onFocusChanged = { focused ->
                        if (focused) {
                            vm.onFieldFocused()
                            restoreKey = null
                        }
                    },
                    // One Back after the keyboard closed exits search.
                    onBack = { backDispatcher?.onBackPressed() },
                ) {
                    Icon(RailItem.Search.icon, null, Modifier.size(26.dp), tint = DroozColors.TextSecondary)
                }
                if (voiceAvailable) {
                    Surface(
                        onClick = {
                            try {
                                voice.launch(voiceIntent)
                            } catch (_: ActivityNotFoundException) {
                                Toast.makeText(context, R.string.search_voice_unavailable, Toast.LENGTH_SHORT).show()
                            }
                        },
                        modifier = Modifier.size(FieldHeight).droozFocus(FocusScale.TILE)
                            .onFocusChanged { if (it.hasFocus) vm.onFieldFocused() },
                        shape = ClickableSurfaceDefaults.shape(DroozCardShape),
                        scale = droozSurfaceScale,
                        colors = droozSurfaceColors(),
                    ) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Icon(MicIcon, stringResource(R.string.search_voice), Modifier.size(28.dp), tint = Color.White)
                        }
                    }
                }
            }
            HeroInfo(state.hero, rowTitle = null, compact = true)
        }

        val content = Modifier.fillMaxSize().padding(top = gridTop)
        when {
            state.items.isNotEmpty() -> {
                // Old results stay (dimmed) while the next query is in flight, so the grid never flashes empty.
                val dim by animateFloatAsState(if (state.loading) 0.5f else 1f, label = "resultsDim")
                ProvideGridPivot {
                    // A new answer is a new grid: scroll and focus memory start fresh.
                    key(state.searchedFor) {
                        PosterGrid(
                            state.items, cellWidth, onOpen, vm::onFocusIndex, content.alpha(dim),
                            entryIndex = vm.focusedIndex,
                            takeFocus = restoreKey != null && restoreKey == state.searchedFor,
                            firstCard = firstCard,
                        )
                    }
                }
            }
            state.loading -> SkeletonGrid(cellWidth, content)
            state.error != null -> ErrorState(
                stringResource(R.string.search_failed), stringResource(R.string.retry),
                {
                    refocusRetry = true
                    jumpToResults = true
                    vm.retry()
                },
                content.padding(bottom = 96.dp).focusRequester(retryRequester).focusGroup(),
            )
            state.isEmptyResult -> Message(
                stringResource(R.string.search_empty),
                stringResource(R.string.search_empty_persian_tip).takeIf { state.searchedFor.orEmpty().hasPersian() },
                content,
            )
            else -> Message(stringResource(R.string.search_idle), stringResource(R.string.search_idle_tip), content)
        }
    }
}

@Composable
private fun Message(title: String, tip: String?, modifier: Modifier) {
    Column(
        modifier.padding(top = 56.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = Color.White)
        tip?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = DroozColors.TextSecondary) }
    }
}

/** Arabic-script letters (Persian queries usually find nothing on the site's search). */
private fun String.hasPersian() = any { it in '؀'..'ۿ' }

/** Persian first, English accepted (titles are often said in English). */
@SuppressLint("InlinedApi")
private fun voiceIntent(prompt: String) = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
    putExtra(RecognizerIntent.EXTRA_LANGUAGE, "fa-IR")
    putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "fa-IR")
    putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE, false)
    // API 34+ recognizers: let the recognizer switch to English mid-request; ignored elsewhere.
    putExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_SWITCH, RecognizerIntent.LANGUAGE_SWITCH_BALANCED)
    putExtra(RecognizerIntent.EXTRA_LANGUAGE_SWITCH_ALLOWED_LANGUAGES, arrayListOf("fa-IR", "en-US"))
    putExtra(RecognizerIntent.EXTRA_PROMPT, prompt)
    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
}

// 24x24 Material "mic" glyph (Apache 2.0).
private val MicIcon: ImageVector = ImageVector.Builder("Mic", 24.dp, 24.dp, 24f, 24f)
    .addPath(
        addPathNodes(
            "M12,14c1.66,0 2.99,-1.34 2.99,-3L15,5c0,-1.66 -1.34,-3 -3,-3S9,3.34 9,5v6c0,1.66 1.34,3 3,3z" +
                "M17.3,11c0,3 -2.54,5.1 -5.3,5.1S6.7,14 6.7,11L5,11c0,3.41 2.72,6.23 6,6.72L11,21h2v-3.28" +
                "c3.28,-0.48 6,-3.3 6,-6.72h-1.7z",
        ),
        fill = SolidColor(Color.White),
    )
    .build()
