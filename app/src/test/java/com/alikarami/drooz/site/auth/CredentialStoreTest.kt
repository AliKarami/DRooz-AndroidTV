package com.alikarami.drooz.site.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CredentialStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    /** Test double: reversible but not plain text. */
    private val box = object : SecretBox {
        override fun encrypt(plain: ByteArray) = ByteArray(plain.size) { (plain[it].toInt() xor 0x5A).toByte() }
        override fun decrypt(cipher: ByteArray) = encrypt(cipher)
    }

    @Test fun roundTrips() {
        val store = CredentialStore(tmp.root.resolve("creds.bin"), box)
        val c = Credentials("testuser", "p@ss wörd", "09120000000")
        store.save(c)
        assertEquals(c, CredentialStore(tmp.root.resolve("creds.bin"), box).load())
    }

    @Test fun fileDoesNotContainPlainPassword() {
        val file = tmp.root.resolve("creds.bin")
        CredentialStore(file, box).save(Credentials("testuser", "hunter2secret", null))
        assertFalse(String(file.readBytes(), Charsets.ISO_8859_1).contains("hunter2secret"))
    }

    @Test fun clearRemoves() {
        val store = CredentialStore(tmp.root.resolve("creds.bin"), box)
        store.save(Credentials("a", "b", null))
        store.clear()
        assertNull(store.load())
    }

    // A save that cannot complete leaves the previous credentials readable, never a truncated file (D5).
    @Test fun failedSaveKeepsThePreviousCredentials() {
        val file = tmp.root.resolve("creds.bin")
        val store = CredentialStore(file, box)
        store.save(Credentials("a", "b", null))
        tmp.newFolder("creds.bin.tmp").resolve("child").writeText("x")
        org.junit.Assert.assertThrows(java.io.IOException::class.java) { store.save(Credentials("c", "d", null)) }
        assertEquals(Credentials("a", "b", null), store.load())
    }

    @Test fun saveLeavesNoTempFile() {
        val store = CredentialStore(tmp.root.resolve("creds.bin"), box)
        store.save(Credentials("a", "b", null))
        assertFalse(tmp.root.resolve("creds.bin.tmp").exists())
    }

    @Test fun corruptFileLoadsNull() {
        val file = tmp.root.resolve("creds.bin").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        assertNull(CredentialStore(file, box).load())
    }
}
