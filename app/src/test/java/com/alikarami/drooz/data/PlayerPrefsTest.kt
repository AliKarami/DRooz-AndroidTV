package com.alikarami.drooz.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PlayerPrefsTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun defaultsMatchTheAccountsSiteSettings() = runTest {
        val prefs = DataStorePlayerPrefs(PreferenceDataStoreFactory.create(scope = backgroundScope) { tmp.newFile("p.preferences_pb") })
        assertEquals(PlayerSettings(SubtitleChoice.FA, SubtitleStyle(100, SubtitleColor.YELLOW, 75), true), prefs.settings.first())
    }

    @Test fun updateRoundTrips() = runTest {
        val prefs = DataStorePlayerPrefs(PreferenceDataStoreFactory.create(scope = backgroundScope) { tmp.newFile("p.preferences_pb") })
        val want = PlayerSettings(SubtitleChoice.DUAL, SubtitleStyle(130, SubtitleColor.CYAN, 25), false)
        prefs.update { it.copy(subtitle = SubtitleChoice.DUAL, style = SubtitleStyle(130, SubtitleColor.CYAN, 25), audioBoost = false) }
        assertEquals(want, prefs.settings.first())
    }

    @Test fun faDubIsNeverPersisted() = runTest {
        val prefs = DataStorePlayerPrefs(PreferenceDataStoreFactory.create(scope = backgroundScope) { tmp.newFile("p.preferences_pb") })
        prefs.update { it.copy(subtitle = SubtitleChoice.FA_DUB) }
        assertEquals(SubtitleChoice.FA, prefs.settings.first().subtitle)
        prefs.update { it.copy(subtitle = SubtitleChoice.EN) }
        prefs.update { it.copy(subtitle = SubtitleChoice.FA_DUB) }
        assertEquals(SubtitleChoice.EN, prefs.settings.first().subtitle)
    }

    @Test fun outOfRangeAndUnknownValuesFallBackToDefaults() = runTest {
        val store = PreferenceDataStoreFactory.create(scope = backgroundScope) { tmp.newFile("p.preferences_pb") }
        store.edit {
            it[intPreferencesKey("subtitle_size")] = 999
            it[intPreferencesKey("subtitle_bg_opacity")] = 33
            it[stringPreferencesKey("subtitle_color")] = "PURPLE"
            it[stringPreferencesKey("subtitle_choice")] = "NOPE"
        }
        assertEquals(PlayerSettings(), DataStorePlayerPrefs(store).settings.first())
    }
}
