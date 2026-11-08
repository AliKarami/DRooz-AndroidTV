package com.alikarami.drooz.site

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Writes [bytes] to a synced temp file next to [file], then renames it over [file]: a kill or power loss
 * leaves the old content or the new one, never a truncated file. Throws [IOException]; the temp file is removed.
 */
internal fun writeAtomically(file: File, bytes: ByteArray) {
    file.parentFile?.mkdirs()
    val tmp = File(file.parentFile, file.name + ".tmp")
    try {
        FileOutputStream(tmp).use { out ->
            out.write(bytes)
            out.fd.sync()
        }
        if (!tmp.renameTo(file)) Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
    } catch (e: IOException) {
        if (tmp.isFile) tmp.delete()
        throw e
    }
}
