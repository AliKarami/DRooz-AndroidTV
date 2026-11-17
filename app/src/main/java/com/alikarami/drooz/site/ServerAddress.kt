package com.alikarami.drooz.site

import android.util.Log
import com.alikarami.drooz.BuildConfig
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.File
import java.io.IOException

/** The site address the user entered on the login screen. */
interface ServerAddress {
    /** The saved address, e.g. `https://host/`; null if none was entered yet. */
    val current: HttpUrl?
    /** Saves [url] and makes it the address every request goes to. */
    fun set(url: HttpUrl)
}

/**
 * [ServerAddress] kept in [file] (no-backup storage) and mirrored into [SiteConfig.base], which the HTTP
 * layer, parsers and image URLs read. [onChanged] runs when a different address replaces the saved one
 * (the old site's cookies mean nothing to the new one).
 */
class FileServerAddress(
    private val file: File,
    private val onChanged: () -> Unit = {},
) : ServerAddress {
    init {
        val saved = try {
            if (file.isFile) file.readText().trim().toHttpUrlOrNull() else null
        } catch (e: IOException) {
            if (BuildConfig.DEBUG) Log.w(TAG, "server address unreadable: $e")
            null
        }
        SiteConfig.base = saved
    }

    override val current: HttpUrl? get() = SiteConfig.base

    @Synchronized
    override fun set(url: HttpUrl) {
        if (url == SiteConfig.base) return
        val changed = SiteConfig.base != null
        try {
            writeAtomically(file, url.toString().toByteArray(Charsets.UTF_8))
        } catch (e: IOException) {
            if (BuildConfig.DEBUG) Log.w(TAG, "server address not saved: $e")
        }
        SiteConfig.base = url
        if (changed) onChanged()
    }

    private companion object {
        const val TAG = "ServerAddress"
    }
}
