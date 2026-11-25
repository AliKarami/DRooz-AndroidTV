package com.alikarami.drooz.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

/** Where the newest release comes from. */
fun interface ReleaseSource {
    /** The latest installable release, or null when there is none. Throws on network / server failure. */
    suspend fun latest(): Release?
}

/** Fetches a release's APK into a file. */
fun interface ApkDownloader {
    /** Downloads [release]'s APK to [target], reporting progress 0..1; throws if it fails or the hash differs. */
    suspend fun download(release: Release, target: File, onProgress: (Float) -> Unit)
}

/** The public repo's releases: no token needed (60 unauthenticated requests an hour per IP is plenty). */
class GitHubReleases(
    private val client: OkHttpClient,
    private val latestUrl: String = "https://api.github.com/repos/AliKarami/DRooz-AndroidTV/releases/latest",
) : ReleaseSource, ApkDownloader {

    override suspend fun latest(): Release? = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(latestUrl)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", USER_AGENT)
            .build()
        client.newCall(req).execute().use { res ->
            // 404: the repo has no published release yet.
            if (res.code == 404) return@withContext null
            if (!res.isSuccessful) throw IOException("GitHub releases: HTTP ${res.code}")
            parseLatestRelease(res.body.string())
        }
    }

    override suspend fun download(release: Release, target: File, onProgress: (Float) -> Unit) = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(release.apkUrl).header("User-Agent", USER_AGENT).build()
        target.parentFile?.mkdirs()
        val part = File(target.path + ".part")
        try {
            client.newCall(req).execute().use { res ->
                if (!res.isSuccessful) throw IOException("APK download: HTTP ${res.code}")
                val body = res.body
                val total = body.contentLength().takeIf { it > 0 } ?: release.size
                val digest = MessageDigest.getInstance("SHA-256")
                var done = 0L
                body.byteStream().use { input ->
                    part.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            coroutineContext.ensureActive()
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            digest.update(buf, 0, n)
                            done += n
                            if (total > 0) onProgress((done.toFloat() / total).coerceAtMost(1f))
                        }
                    }
                }
                val hex = digest.digest().joinToString("") { "%02x".format(it) }
                if (release.sha256 != null && hex != release.sha256) throw IOException("APK hash mismatch")
            }
            target.delete()
            if (!part.renameTo(target)) throw IOException("Can't move the downloaded APK into place")
        } finally {
            part.delete()
        }
    }

    private companion object {
        const val USER_AGENT = "DRooz-AndroidTV"
    }
}
