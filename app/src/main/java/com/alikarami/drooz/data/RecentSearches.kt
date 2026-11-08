package com.alikarami.drooz.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.alikarami.drooz.site.parse.PersianText
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

/** «جستجوهای اخیر»: queries the user opened a result from, newest first. */
interface RecentSearches {
    val queries: Flow<List<String>>
    suspend fun add(query: String)
    suspend fun clear()
}

/** How many recent queries are kept. */
const val RECENT_SEARCHES_MAX = 8

private val SPACES = Regex("""\s+""")

/**
 * [list] with [query] (trimmed, inner whitespace collapsed) moved or added to the front, keeping its new
 * spelling; a repeat is matched ignoring case and Persian spelling variants. A blank query changes nothing.
 */
fun withRecentQuery(list: List<String>, query: String): List<String> {
    val q = query.trim().replace(SPACES, " ")
    if (q.isEmpty()) return list
    val key = recentKey(q)
    return (listOf(q) + list.filter { recentKey(it) != key }).take(RECENT_SEARCHES_MAX)
}

private fun recentKey(q: String) = PersianText.normalize(q).lowercase()

private val Context.recentSearchesDataStore: DataStore<Preferences> by preferencesDataStore(
    "recent_searches", corruptionHandler = prefsCorruptionHandler(),
)

fun recentSearchesStore(context: Context): DataStore<Preferences> = context.applicationContext.recentSearchesDataStore

/** One string key, newline-separated (queries never hold a newline). An unreadable store reads as no history. */
class DataStoreRecentSearches(private val store: DataStore<Preferences>) : RecentSearches {
    private val key = stringPreferencesKey("queries")

    override val queries: Flow<List<String>> = store.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { p -> p[key]?.split('\n')?.filter { it.isNotBlank() }.orEmpty() }

    override suspend fun add(query: String) {
        store.edit { p ->
            val now = p[key]?.split('\n')?.filter { it.isNotBlank() }.orEmpty()
            p[key] = withRecentQuery(now, query).joinToString("\n")
        }
    }

    override suspend fun clear() {
        store.edit { it.remove(key) }
    }
}
