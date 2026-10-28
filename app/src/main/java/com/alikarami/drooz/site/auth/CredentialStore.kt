package com.alikarami.drooz.site.auth

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
    fun save(c: Credentials) {
        file.parentFile?.mkdirs()
        file.writeBytes(box.encrypt(Json.encodeToString(Credentials.serializer(), c).toByteArray()))
    }

    fun load(): Credentials? = runCatching {
        Json.decodeFromString(Credentials.serializer(), String(box.decrypt(file.readBytes())))
    }.getOrNull()

    fun clear() {
        file.delete()
    }
}
