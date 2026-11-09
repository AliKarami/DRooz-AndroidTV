package com.alikarami.drooz.data

import android.util.Log
import com.alikarami.drooz.BuildConfig
import com.alikarami.drooz.site.writeAtomically
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException

/**
 * Titles the user removed from «ادامه تماشا» on this TV, by postId, with when. The site keeps listing
 * them; [ContinueWatching.order] leaves one out until it is played again after the hide.
 */
interface HiddenTitles {
    /** postId → when it was hidden (wall clock, as [WatchEntry.updatedAt]). */
    suspend fun all(): Map<Long, Long>
    suspend fun hide(postId: Long, at: Long)
}

const val HIDDEN_TITLES_CAP = 200

@Serializable
private data class HiddenTitle(val postId: Long, val hiddenAt: Long)

/** One small JSON file, written atomically; an unreadable file just means nothing is hidden. */
class FileHiddenTitles(private val file: File) : HiddenTitles {
    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(HiddenTitle.serializer())
    private var hidden: MutableMap<Long, Long>? = null

    override suspend fun all(): Map<Long, Long> = mutex.withLock { loaded().toMap() }

    override suspend fun hide(postId: Long, at: Long) = mutex.withLock {
        val map = loaded()
        map[postId] = at
        if (map.size > HIDDEN_TITLES_CAP) {
            map.entries.sortedBy { it.value }.take(map.size - HIDDEN_TITLES_CAP).map { it.key }.forEach(map::remove)
        }
        persist(map)
    }

    /** Forgets every hide, on disk too (another account signed in on this TV). */
    suspend fun clear() = mutex.withLock {
        hidden = LinkedHashMap()
        withContext(Dispatchers.IO) { if (file.exists() && !file.delete()) write(emptyMap()) }
    }

    /** Caller holds the mutex. */
    private suspend fun loaded(): MutableMap<Long, Long> = hidden ?: withContext(Dispatchers.IO) { read() }.also { hidden = it }

    private fun read(): MutableMap<Long, Long> {
        val map = LinkedHashMap<Long, Long>()
        if (!file.exists()) return map
        try {
            val text = file.readText()
            if (text.isNotBlank()) json.decodeFromString(serializer, text).forEach { map[it.postId] = it.hiddenAt }
        } catch (e: IOException) {
            if (BuildConfig.DEBUG) Log.w(TAG, "read failed: $e")
        } catch (e: IllegalArgumentException) {
            // SerializationException included. Losing the hides only brings the cards back.
            if (BuildConfig.DEBUG) Log.w(TAG, "decode failed: $e")
        }
        return map
    }

    /** A disk error keeps the hide in memory for this session. */
    private suspend fun persist(map: Map<Long, Long>) = withContext(Dispatchers.IO) {
        try {
            write(map)
        } catch (e: IOException) {
            if (BuildConfig.DEBUG) Log.w(TAG, "persist failed: $e")
        }
    }

    private fun write(map: Map<Long, Long>) {
        val list = map.map { (id, at) -> HiddenTitle(id, at) }
        writeAtomically(file, json.encodeToString(serializer, list).toByteArray(Charsets.UTF_8))
    }

    private companion object {
        const val TAG = "HiddenTitles"
    }
}
