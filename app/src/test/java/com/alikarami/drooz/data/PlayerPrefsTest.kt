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
        prefs.update { it.copy(subtitle = SubtitleChoice.FA_DUB, audioBoost = false) }
        val after = prefs.settings.first()
        assertEquals(SubtitleChoice.EN, after.subtitle)
        assertEquals(false, after.audioBoost)
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

    // A corrupt preferences file is replaced by empty preferences instead of failing every read (D4).
    @Test fun corruptFileResetsToDefaults() = runTest {
        val file = tmp.newFile("c.preferences_pb").apply { writeBytes(byteArrayOf(0x7f, 0x00, 0x13, 0x37, 0x42)) }
        val store = PreferenceDataStoreFactory.create(corruptionHandler = prefsCorruptionHandler(), scope = backgroundScope) { file }
        assertEquals(PlayerSettings(), DataStorePlayerPrefs(store).settings.first())
        val prefs = DataStoreTitlePrefs(store)
        prefs.setDefaultDub(true)
        assertEquals(true, prefs.defaultDub())
    }

    private class FailingStore : androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences> {
        override val data = kotlinx.coroutines.flow.flow<androidx.datastore.preferences.core.Preferences> {
            throw java.io.IOException("disk")
        }
        override suspend fun updateData(
            transform: suspend (androidx.datastore.preferences.core.Preferences) -> androidx.datastore.preferences.core.Preferences,
        ) = throw java.io.IOException("disk")
    }

    // Title prefs are read on the way to playback: a read error means "no choice", never a crash (D4).
    @Test fun titlePrefsReadErrorsGiveDefaults() = runTest {
        val prefs = DataStoreTitlePrefs(FailingStore())
        assertEquals(null, prefs.dubSelected(1))
        assertEquals(false, prefs.defaultDub())
    }

    @Test fun titlePrefsRoundTripAndClear() = runTest {
        val prefs = DataStoreTitlePrefs(PreferenceDataStoreFactory.create(scope = backgroundScope) { tmp.newFile("t.preferences_pb") })
        prefs.setDubSelected(7, true)
        prefs.setDefaultDub(true)
        assertEquals(true, prefs.dubSelected(7))
        prefs.clear()
        assertEquals(null, prefs.dubSelected(7))
        assertEquals(false, prefs.defaultDub())
    }
}
