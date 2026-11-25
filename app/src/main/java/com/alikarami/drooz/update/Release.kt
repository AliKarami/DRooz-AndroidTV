package com.alikarami.drooz.update

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** A `major.minor.patch` version; a leading `v` and any `-suffix` are ignored. */
data class AppVersion(val major: Int, val minor: Int, val patch: Int) : Comparable<AppVersion> {
    override fun compareTo(other: AppVersion): Int =
        compareValuesBy(this, other, AppVersion::major, AppVersion::minor, AppVersion::patch)

    override fun toString() = "$major.$minor.$patch"

    companion object {
        private val PATTERN = Regex("""^v?(\d+)\.(\d+)(?:\.(\d+))?(?:[-+].*)?$""")

        fun parse(text: String): AppVersion? = PATTERN.matchEntire(text.trim())?.let { m ->
            val (a, b, c) = m.destructured
            AppVersion(a.toInt(), b.toInt(), c.ifEmpty { "0" }.toInt())
        }
    }
}

/** A published release that carries the app's APK. */
data class Release(
    val version: AppVersion,
    val notes: String,
    val apkUrl: String,
    /** Lower-case hex SHA-256 of the APK, when GitHub reports one. */
    val sha256: String?,
    val size: Long,
)

/** The release asset the app installs (the README's download link points at the same name). */
const val APK_ASSET_NAME = "DRooz.apk"

@Serializable
private data class GhRelease(
    @SerialName("tag_name") val tag: String,
    val body: String? = null,
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    val assets: List<GhAsset> = emptyList(),
)

@Serializable
private data class GhAsset(
    val name: String,
    val size: Long = 0,
    @SerialName("browser_download_url") val url: String,
    val digest: String? = null,
)

private val json = Json { ignoreUnknownKeys = true }

/**
 * GitHub's `releases/latest` answer as a [Release], or null when it isn't one the app can install: a draft
 * or pre-release, a tag that isn't a version, or no [APK_ASSET_NAME] asset. Throws on malformed JSON.
 */
fun parseLatestRelease(body: String): Release? {
    val r = json.decodeFromString<GhRelease>(body)
    if (r.draft || r.prerelease) return null
    val version = AppVersion.parse(r.tag) ?: return null
    val apk = r.assets.firstOrNull { it.name == APK_ASSET_NAME } ?: return null
    val sha = apk.digest?.takeIf { it.startsWith("sha256:") }?.removePrefix("sha256:")?.lowercase()
    return Release(version, releaseNotes(r.body.orEmpty()), apk.url, sha, apk.size)
}

private val HEADING = Regex("""^#+\s.*$""")
private val BOLD = Regex("""\*\*(.+?)\*\*""")
private val ISSUE_REFS = Regex("""\s*\((?:#\d+[^)]*)\)""")

/**
 * The release body as plain lines for the TV: headings and the closing "Requires …" line dropped, bold
 * markers and `(#5, thanks …)` credits removed, bullets kept as «•».
 */
fun releaseNotes(markdown: String): String = markdown.lineSequence()
    .map { it.trim() }
    .filter { it.isNotEmpty() && !HEADING.matches(it) && !it.startsWith("Requires ") }
    .map { line ->
        val text = line.replace(BOLD, "$1").replace(ISSUE_REFS, "")
        if (text.startsWith("- ") || text.startsWith("* ")) "• " + text.substring(2) else text
    }
    .joinToString("\n")
