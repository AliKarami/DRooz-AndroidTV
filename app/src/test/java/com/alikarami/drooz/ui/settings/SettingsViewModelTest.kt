package com.alikarami.drooz.ui.settings

import com.alikarami.drooz.data.AuthApi
import com.alikarami.drooz.data.CacheCleaner
import com.alikarami.drooz.data.PlayerPrefs
import com.alikarami.drooz.data.PlayerSettings
import com.alikarami.drooz.data.SubtitleChoice
import com.alikarami.drooz.data.SubtitleColor
import com.alikarami.drooz.data.SubtitleStyle
import com.alikarami.drooz.data.TitlePrefs
import com.alikarami.drooz.site.auth.LoginResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    private class FakePlayerPrefs(initial: PlayerSettings = PlayerSettings()) : PlayerPrefs {
        val state = MutableStateFlow(initial)
        override val settings = state
        override suspend fun update(transform: (PlayerSettings) -> PlayerSettings) {
            val next = transform(state.value)
            // Like the real store, FA_DUB is never kept.
            state.value = if (next.subtitle == SubtitleChoice.FA_DUB) next.copy(subtitle = state.value.subtitle) else next
        }
    }

    private class FakeTitlePrefs : TitlePrefs {
        var dub = false
        override suspend fun dubSelected(postId: Long): Boolean? = null
        override suspend fun setDubSelected(postId: Long, selected: Boolean) {}
        override suspend fun defaultDub() = dub
        override suspend fun setDefaultDub(on: Boolean) { dub = on }
    }

    private class FakeAuth(var failLogout: Boolean = false) : AuthApi {
        var logouts = 0
        override val isLoggedIn = true
        override val username: String? = "ali"
        override suspend fun login(username: String, password: String, mobile: String?) = LoginResult.Success
        override suspend fun logout() {
            logouts++
            if (failLogout) throw IOException("offline")
        }
    }

    private class FakeCache : CacheCleaner {
        var clears = 0
        override suspend fun clear() { clears++ }
    }

    private class Rig(
        val prefs: FakePlayerPrefs = FakePlayerPrefs(),
        val titlePrefs: FakeTitlePrefs = FakeTitlePrefs(),
        val auth: FakeAuth = FakeAuth(),
        val cache: FakeCache = FakeCache(),
    ) {
        val vm = SettingsViewModel(prefs, titlePrefs, auth, cache, "0.1.0")
    }

    private fun TestScope.events(vm: SettingsViewModel): MutableList<SettingsEvent> {
        val got = mutableListOf<SettingsEvent>()
        backgroundScope.launch(dispatcher) { vm.events.collect { got += it } }
        runCurrent()
        return got
    }

    private fun TestScope.started(rig: Rig): SettingsViewModel {
        backgroundScope.launch(dispatcher) { rig.vm.uiState.collect {} }
        runCurrent()
        return rig.vm
    }

    @Test fun exposesUsernameAndVersion() = runTest(dispatcher) {
        val vm = started(Rig())
        assertEquals("ali", vm.uiState.value.username)
        assertEquals("0.1.0", vm.uiState.value.version)
    }

    @Test fun sizeStep_clampsToRange() = runTest(dispatcher) {
        val rig = Rig(prefs = FakePlayerPrefs(PlayerSettings(style = SubtitleStyle(sizePercent = 200))))
        val vm = started(rig)
        vm.stepSize(+1); runCurrent()
        assertEquals(200, rig.prefs.state.value.style.sizePercent)
        vm.stepSize(-1); runCurrent()
        assertEquals(190, rig.prefs.state.value.style.sizePercent)
        assertEquals(190, vm.uiState.value.player.style.sizePercent)

        val low = Rig(prefs = FakePlayerPrefs(PlayerSettings(style = SubtitleStyle(sizePercent = 60))))
        val vm2 = started(low)
        vm2.stepSize(-1); runCurrent()
        assertEquals(60, low.prefs.state.value.style.sizePercent)
    }

    @Test fun subtitleChoice_faDubIgnored() = runTest(dispatcher) {
        val rig = Rig()
        val vm = started(rig)
        vm.setSubtitle(SubtitleChoice.EN); runCurrent()
        vm.setSubtitle(SubtitleChoice.FA_DUB); runCurrent()
        assertEquals(SubtitleChoice.EN, rig.prefs.state.value.subtitle)
        vm.setSubtitle(SubtitleChoice.OFF); runCurrent()
        assertEquals(SubtitleChoice.OFF, vm.uiState.value.player.subtitle)
    }

    @Test fun colorAndBackground_persist() = runTest(dispatcher) {
        val rig = Rig()
        val vm = started(rig)
        vm.setColor(SubtitleColor.CYAN); vm.setBackground(25); runCurrent()
        assertEquals(SubtitleColor.CYAN, rig.prefs.state.value.style.color)
        assertEquals(25, rig.prefs.state.value.style.backgroundOpacity)
        vm.setBackground(33); runCurrent() // not one of BACKGROUND_OPACITIES
        assertEquals(25, rig.prefs.state.value.style.backgroundOpacity)
    }

    @Test fun audioBoost_persists() = runTest(dispatcher) {
        val rig = Rig()
        val vm = started(rig)
        vm.setAudioBoost(false); runCurrent()
        assertFalse(rig.prefs.state.value.audioBoost)
        assertFalse(vm.uiState.value.player.audioBoost)
    }

    @Test fun defaultDub_persists() = runTest(dispatcher) {
        val rig = Rig()
        val vm = started(rig)
        assertFalse(vm.uiState.value.defaultDub)
        vm.setDefaultDub(true); runCurrent()
        assertTrue(rig.titlePrefs.dub)
        assertTrue(vm.uiState.value.defaultDub)
    }

    @Test fun defaultDub_loadedFromPrefs() = runTest(dispatcher) {
        val vm = started(Rig(titlePrefs = FakeTitlePrefs().apply { dub = true }))
        assertTrue(vm.uiState.value.defaultDub)
    }

    @Test fun clearCache_callsCleaner_andFlags() = runTest(dispatcher) {
        val rig = Rig()
        val vm = started(rig)
        assertFalse(vm.uiState.value.cacheCleared)
        vm.clearCache(); runCurrent()
        assertEquals(1, rig.cache.clears)
        assertTrue(vm.uiState.value.cacheCleared)
        assertFalse(vm.uiState.value.busy)
    }

    @Test fun logout_needsConfirmation_thenEmitsLoggedOut() = runTest(dispatcher) {
        val rig = Rig()
        val vm = started(rig)
        val got = events(vm)
        vm.requestLogout(); runCurrent()
        assertTrue(vm.uiState.value.confirmLogout)
        assertEquals(0, rig.auth.logouts)

        vm.cancelLogout(); runCurrent()
        assertFalse(vm.uiState.value.confirmLogout)
        assertEquals(0, rig.auth.logouts)

        vm.requestLogout(); vm.confirmLogout(); runCurrent()
        assertEquals(1, rig.auth.logouts)
        assertEquals(listOf<SettingsEvent>(SettingsEvent.LoggedOut), got)
    }

    @Test fun logout_failure_stillLogsOut() = runTest(dispatcher) {
        val rig = Rig(auth = FakeAuth(failLogout = true))
        val vm = started(rig)
        val got = events(vm)
        vm.requestLogout(); vm.confirmLogout(); runCurrent()
        assertEquals(listOf<SettingsEvent>(SettingsEvent.LoggedOut), got)
    }
}
