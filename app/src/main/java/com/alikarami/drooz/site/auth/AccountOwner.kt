package com.alikarami.drooz.site.auth

import com.alikarami.drooz.site.writeAtomically
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * Remembers which account the local per-account data (watch progress, title choices) belongs to, across
 * logouts. [file] holds a SHA-256 of the username, not the name itself.
 */
class AccountOwner(private val file: File) {
    /**
     * Makes [username] the owner. True when a different account owned the data before (the caller clears it);
     * false for the same account, or when no owner was recorded yet (data from before this marker is kept).
     */
    @Synchronized
    fun claim(username: String): Boolean {
        val id = hash(username)
        val previous = try {
            if (file.isFile) file.readText().trim().ifEmpty { null } else null
        } catch (_: IOException) {
            null
        }
        if (previous == id) return false
        try {
            writeAtomically(file, id.toByteArray(Charsets.UTF_8))
        } catch (_: IOException) {
            // Unrecorded: the next login compares against the old owner again, which only repeats a clear.
        }
        return previous != null
    }

    private fun hash(username: String): String =
        MessageDigest.getInstance("SHA-256").digest(username.trim().lowercase().toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
