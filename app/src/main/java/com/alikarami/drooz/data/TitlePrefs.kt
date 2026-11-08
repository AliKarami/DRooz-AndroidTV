package com.alikarami.drooz.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import java.io.IOException

/** Per-title viewing choices that survive leaving the title and process death. */
interface TitlePrefs {
    /** Null when the user never chose for this title. */
    suspend fun dubSelected(postId: Long): Boolean?
    suspend fun setDubSelected(postId: Long, selected: Boolean)
    /** Global default for titles with no per-title choice. */
    suspend fun defaultDub(): Boolean
    suspend fun setDefaultDub(on: Boolean)
}

private val Context.titlePrefsDataStore: DataStore<Preferences> by preferencesDataStore(
    "title_prefs", corruptionHandler = prefsCorruptionHandler(),
)

fun titlePrefsStore(context: Context): DataStore<Preferences> = context.applicationContext.titlePrefsDataStore

/** Reads never throw for a disk error: an unreadable store means "no choice made". */
class DataStoreTitlePrefs(private val store: DataStore<Preferences>) : TitlePrefs {
    private fun key(postId: Long) = booleanPreferencesKey("dub_$postId")

    private suspend fun read(): Preferences = try {
        store.data.first()
    } catch (_: IOException) {
        emptyPreferences()
    }

    override suspend fun dubSelected(postId: Long): Boolean? = read()[key(postId)]
    override suspend fun setDubSelected(postId: Long, selected: Boolean) {
        store.edit { it[key(postId)] = selected }
    }

    private val defaultDubKey = booleanPreferencesKey("default_dub")
    override suspend fun defaultDub(): Boolean = read()[defaultDubKey] ?: false
    override suspend fun setDefaultDub(on: Boolean) {
        store.edit { it[defaultDubKey] = on }
    }

    /** Forgets every choice (another account signed in on this TV). */
    suspend fun clear() {
        store.edit { it.clear() }
    }
}
