package com.alikarami.drooz.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

/** Per-title viewing choices that survive leaving the title and process death. */
interface TitlePrefs {
    /** Null when the user never chose for this title. */
    suspend fun dubSelected(postId: Long): Boolean?
    suspend fun setDubSelected(postId: Long, selected: Boolean)
    /** Global default for titles with no per-title choice. */
    suspend fun defaultDub(): Boolean
    suspend fun setDefaultDub(on: Boolean)
}

private val Context.titlePrefsStore: DataStore<Preferences> by preferencesDataStore("title_prefs")

class DataStoreTitlePrefs(context: Context) : TitlePrefs {
    private val store = context.applicationContext.titlePrefsStore
    private fun key(postId: Long) = booleanPreferencesKey("dub_$postId")

    override suspend fun dubSelected(postId: Long): Boolean? = store.data.first()[key(postId)]
    override suspend fun setDubSelected(postId: Long, selected: Boolean) {
        store.edit { it[key(postId)] = selected }
    }

    private val defaultDubKey = booleanPreferencesKey("default_dub")
    override suspend fun defaultDub(): Boolean = store.data.first()[defaultDubKey] ?: false
    override suspend fun setDefaultDub(on: Boolean) {
        store.edit { it[defaultDubKey] = on }
    }
}
