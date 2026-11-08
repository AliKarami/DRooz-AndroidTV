package com.alikarami.drooz.site.auth

import android.util.Log
import com.alikarami.drooz.BuildConfig
import com.alikarami.drooz.site.writeAtomically
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

interface SecretBox {
    fun encrypt(plain: ByteArray): ByteArray
    fun decrypt(cipher: ByteArray): ByteArray
}

@Serializable
data class Credentials(val username: String, val password: String, val mobile: String?)

/** Stores the login credentials encrypted with [box], so expired sessions can be renewed silently. */
class CredentialStore(private val file: File, private val box: SecretBox) {
    /** Atomic: a kill mid-save keeps the previous credentials, never a truncated file. */
    fun save(c: Credentials) {
        writeAtomically(file, box.encrypt(Json.encodeToString(Credentials.serializer(), c).toByteArray()))
    }

    /** Null when there are none or they cannot be read (corrupt file, invalidated Keystore key). */
    fun load(): Credentials? {
        if (!file.exists()) return null
        return runCatching {
            Json.decodeFromString(Credentials.serializer(), String(box.decrypt(file.readBytes())))
        }.onFailure { if (BuildConfig.DEBUG) Log.w("CredentialStore", "stored credentials unreadable: ${it.javaClass.simpleName}") }
            .getOrNull()
    }

    fun clear() {
        file.delete()
    }
}
