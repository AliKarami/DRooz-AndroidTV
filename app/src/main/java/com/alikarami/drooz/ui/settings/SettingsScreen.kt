package com.alikarami.drooz.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.alikarami.drooz.R
import com.alikarami.drooz.data.BACKGROUND_OPACITIES
import com.alikarami.drooz.data.SUBTITLE_SIZES
import com.alikarami.drooz.data.SubtitleChoice
import com.alikarami.drooz.data.SubtitleStyle
import com.alikarami.drooz.player.SubtitleCues
import com.alikarami.drooz.player.ui.ColorRow
import com.alikarami.drooz.player.ui.StepperRow
import com.alikarami.drooz.player.ui.SubtitleLayer
import com.alikarami.drooz.player.ui.SubtitlePlacement
import com.alikarami.drooz.player.ui.subtitleChoiceLabel
import com.alikarami.drooz.ui.common.PillButton
import com.alikarami.drooz.ui.common.SegmentSwitch
import com.alikarami.drooz.ui.common.TitleText
import com.alikarami.drooz.ui.common.UiStrings
import com.alikarami.drooz.ui.common.requestFocusSoon
import com.alikarami.drooz.ui.theme.DroozColors

/** The subtitle choices settings offers (dub is per title, chosen on the title page). */
private val LANGUAGES = listOf(SubtitleChoice.FA, SubtitleChoice.EN, SubtitleChoice.DUAL, SubtitleChoice.OFF)
private val PreviewHeight = 120.dp
private val PreviewShape = RoundedCornerShape(10.dp)

/**
 * «تنظیمات»: subtitle, audio, storage, account and about sections in one scrolling list. Changes apply at
 * once. Logout asks first in a centred overlay; Back closes it, otherwise Back leaves the screen.
 */
@Composable
fun SettingsScreen(vm: SettingsViewModel) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    val first = remember { FocusRequester() }
    val logout = remember { FocusRequester() }
    LaunchedEffect(Unit) { first.requestFocusSoon() }

    // Back closes the dialog. While a logout is in flight Back is swallowed: leaving would clear the view
    // model, so its LoggedOut would never reach the nav host (logged out, yet still on Home).
    BackHandler(enabled = state.confirmLogout || state.busy) { if (state.confirmLogout) vm.cancelLogout() }
    // Closing the dialog hands focus back to the button that opened it.
    var dialogWasOpen by remember { mutableStateOf(false) }
    LaunchedEffect(state.confirmLogout) {
        if (state.confirmLogout) {
            dialogWasOpen = true
        } else if (dialogWasOpen) {
            dialogWasOpen = false
            logout.requestFocusSoon()
        }
    }

    Box(Modifier.fillMaxSize().background(DroozColors.Background)) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 64.dp, vertical = 36.dp),
        ) {
            Text(
                stringResource(R.string.rail_settings), Modifier.padding(start = 12.dp, bottom = 8.dp),
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold), color = Color.White,
            )
            Column(Modifier.widthIn(max = 760.dp)) {
                SubtitleSection(state, vm, first)
                AudioSection(state, vm)
                StorageSection(state, vm)
                AccountSection(state, vm, logout)
                Section(R.string.settings_section_about) {
                    Text(
                        stringResource(R.string.settings_version, state.version),
                        Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.bodyMedium, color = DroozColors.TextSecondary,
                    )
                }
            }
        }
        if (state.confirmLogout) LogoutDialog(onConfirm = vm::confirmLogout, onCancel = vm::cancelLogout)
    }
}

@Composable
private fun SubtitleSection(state: SettingsUiState, vm: SettingsViewModel, first: FocusRequester) {
    val style = state.player.style
    Section(R.string.settings_section_subtitle) {
        SettingRow(stringResource(R.string.settings_sub_language)) {
            LANGUAGES.forEachIndexed { i, c ->
                OptionChip(
                    subtitleChoiceLabel(c), c == state.player.subtitle, { vm.setSubtitle(c) },
                    if (i == 0) Modifier.focusRequester(first) else Modifier,
                )
            }
        }
        val sizes = remember { SUBTITLE_SIZES.toList() }
        val sizeIndex = sizes.indexOf(style.sizePercent)
        StepperRow(
            stringResource(R.string.player_size), UiStrings.percent(style.sizePercent),
            // An off-list size steps from 100 % (as the view model does), so both directions stay open.
            canDecrease = sizeIndex != 0, canIncrease = sizeIndex != sizes.lastIndex,
            onStep = vm::stepSize,
        )
        ColorRow(style.color, vm::setColor)
        SettingRow(stringResource(R.string.player_background)) {
            BACKGROUND_OPACITIES.forEach { o ->
                OptionChip(UiStrings.percent(o), o == style.backgroundOpacity, { vm.setBackground(o) })
            }
        }
        SubtitlePreview(style)
    }
}

@Composable
private fun AudioSection(state: SettingsUiState, vm: SettingsViewModel) {
    Section(R.string.settings_section_audio) {
        SettingRow(stringResource(R.string.player_boost)) {
            // Read the latest state on click: two quick presses must toggle twice.
            OnOffSwitch(state.player.audioBoost) { vm.setAudioBoost(!vm.uiState.value.player.audioBoost) }
        }
        SettingRow(stringResource(R.string.settings_default_dub)) {
            OnOffSwitch(state.defaultDub) { vm.setDefaultDub(!vm.uiState.value.defaultDub) }
        }
    }
}

@Composable
private fun StorageSection(state: SettingsUiState, vm: SettingsViewModel) {
    Section(R.string.settings_section_storage) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            PillButton(stringResource(R.string.settings_clear_cache), vm::clearCache)
            if (state.cacheCleared) {
                Text(
                    "✓ " + stringResource(R.string.settings_cache_cleared),
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold), color = DroozColors.Watched,
                )
            }
        }
    }
}

@Composable
private fun AccountSection(state: SettingsUiState, vm: SettingsViewModel, logout: FocusRequester) {
    Section(R.string.settings_section_account) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Its own Text, so the Latin name is laid out on its own (isolated from the Persian around it).
            state.username?.let { TitleText(it, MaterialTheme.typography.bodyLarge, color = Color.White, maxLines = 1) }
            Spacer(Modifier.weight(1f))
            PillButton(stringResource(R.string.settings_logout), vm::requestLogout, Modifier.focusRequester(logout))
        }
    }
}

@Composable
private fun Section(title: Int, content: @Composable ColumnScope.() -> Unit) {
    Text(
        stringResource(title), Modifier.padding(start = 12.dp, top = 24.dp, bottom = 6.dp),
        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold), color = DroozColors.Accent,
    )
    Column(content = content)
}

/** Label at the start, controls at the end (as the player panel's colour and stepper rows). */
@Composable
private fun SettingRow(label: String, controls: @Composable RowScope.() -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = Color.White)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), content = controls)
    }
}

/** A title-page pill; ✓ marks the current choice (as in the player's panel). */
@Composable
private fun OptionChip(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    PillButton(if (selected) "✓ $label" else label, onClick, modifier)
}

@Composable
private fun OnOffSwitch(on: Boolean, onToggle: () -> Unit) {
    SegmentSwitch(stringResource(R.string.player_off), stringResource(R.string.player_on), on, onToggle)
}

/**
 * The sample line through the player's own subtitle renderer. The renderer sizes text from its height, so
 * it gets a full screen's height, bottom-aligned and clipped to the strip: the sample shows at play size.
 */
@Composable
private fun SubtitlePreview(style: SubtitleStyle) {
    val sample = stringResource(R.string.settings_sub_preview)
    val screenH = LocalConfiguration.current.screenHeightDp.dp
    val bottomPx = with(LocalDensity.current) { 20.dp.roundToPx() }
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .height(PreviewHeight)
            .clip(PreviewShape)
            .background(Brush.verticalGradient(listOf(Color(0xFF34404E), Color(0xFF5B4A3B)))),
    ) {
        Box(Modifier.fillMaxWidth().wrapContentHeight(Alignment.Bottom, unbounded = true).height(screenH)) {
            SubtitleLayer(SubtitleCues(primary = sample), style, SubtitlePlacement(bottomPx, null), preview = false)
        }
    }
}

/** A centred confirm panel over the screen; focus starts on «انصراف» and cannot leave the panel. */
@Composable
private fun LogoutDialog(onConfirm: () -> Unit, onCancel: () -> Unit) {
    val cancel = remember { FocusRequester() }
    LaunchedEffect(Unit) { cancel.requestFocusSoon() }
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.7f)), contentAlignment = Alignment.Center) {
        Column(
            Modifier
                .widthIn(min = 360.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(DroozColors.Surface)
                .focusProperties { onExit = { cancelFocusChange() } }
                .focusGroup()
                .padding(horizontal = 32.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                stringResource(R.string.settings_logout_confirm),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold), color = Color.White,
            )
            Row(Modifier.padding(top = 24.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PillButton(stringResource(R.string.settings_logout_yes), onConfirm)
                PillButton(stringResource(R.string.settings_cancel), onCancel, Modifier.focusRequester(cancel))
            }
        }
    }
}
