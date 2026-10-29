package com.alikarami.drooz.data

import android.util.Log
import com.alikarami.drooz.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

@Serializable
data class WatchEntry(
    val streamId: String,
    val refPath: String,
    val season: Int,
    val episode: Int,
    val positionMs: Long,
    val durationMs: Long,
    val updatedAt: Long,
    val finished: Boolean,
    val subtitleOffsetMs: Long = 0,
)

/** Local per-stream watch state. */
interface WatchStore {
    suspend fun get(streamId: String): WatchEntry?
    /** Inserts or replaces by streamId; stamps updatedAt from the store's clock. */
    suspend fun put(entry: WatchEntry)
    /** Newest first. */
    suspend fun all(): List<WatchEntry>
}

const val WATCH_STORE_CAP = 500

class FileWatchStore(
    private val file: File,
    private val clock: () -> Long = System::currentTimeMillis,
) : WatchStore {
    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(WatchEntry.serializer())
    private var entries: MutableMap<String, WatchEntry>? = null

    override suspend fun get(streamId: String): WatchEntry? = mutex.withLock { loaded()[streamId] }

    override suspend fun put(entry: WatchEntry) = mutex.withLock {
        val map = loaded()
        map[entry.streamId] = entry.copy(updatedAt = clock())
        if (map.size > WATCH_STORE_CAP) {
            map.values.sortedBy { it.updatedAt }.take(map.size - WATCH_STORE_CAP).forEach { map.remove(it.streamId) }
        }
        try {
            persist(map.values.toList())
        } catch (e: IOException) {
            // Playback must never crash on a disk error; the entry stays in memory.
            if (BuildConfig.DEBUG) Log.w(TAG, "persist failed: $e")
        }
    }

    override suspend fun all(): List<WatchEntry> = mutex.withLock { loaded().values.sortedByDescending { it.updatedAt } }

    /** Caller holds the mutex. */
    private suspend fun loaded(): MutableMap<String, WatchEntry> = entries ?: withContext(Dispatchers.IO) {
        val list = read()
        LinkedHashMap<String, WatchEntry>().also { m -> list.forEach { m[it.streamId] = it } }
    }.also { entries = it }

    /**
     * The stored entries; none when the file is missing or empty. A file that can't be decoded, or still
     * can't be read on a second try, is moved aside to `<name>.corrupt` so the next put never overwrites it.
     */
    private fun read(): List<WatchEntry> {
        if (!file.exists()) return emptyList()
        val text = try {
            file.readText()
        } catch (e: IOException) {
            try {
                file.readText()
            } catch (e2: IOException) {
                if (BuildConfig.DEBUG) Log.w(TAG, "read failed twice: $e2")
                moveAside()
                return emptyList()
            }
        }
        if (text.isBlank()) return emptyList()
        return try {
            json.decodeFromString(serializer, text)
        } catch (e: IllegalArgumentException) {
            // SerializationException included.
            if (BuildConfig.DEBUG) Log.w(TAG, "decode failed: $e")
            moveAside()
            emptyList()
        }
    }

    private fun moveAside() {
        val aside = File(file.parentFile, file.name + ".corrupt")
        try {
            if (aside.exists()) aside.deleteRecursively()
            Files.move(file.toPath(), aside.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } catch (e: IOException) {
            if (BuildConfig.DEBUG) Log.w(TAG, "could not keep the unreadable file: $e")
        }
    }

    /** Writes a synced temp file, then renames it over the real one (never a half-written store). */
    private suspend fun persist(list: List<WatchEntry>) = withContext(Dispatchers.IO) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        try {
            FileOutputStream(tmp).use { out ->
                out.write(json.encodeToString(serializer, list).toByteArray(Charsets.UTF_8))
                out.fd.sync()
            }
            if (!tmp.renameTo(file)) {
                Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (e: IOException) {
            tmp.delete()
            throw e
        }
    }

    private companion object {
        const val TAG = "WatchStore"
    }
}
