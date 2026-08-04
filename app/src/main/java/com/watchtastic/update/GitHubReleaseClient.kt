package com.watchtastic.update

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** One published APK, wherever it came from. */
data class RemoteBuild(
    val downloadUrl: String,
    val fileName: String,
    val version: Version,
    /** Release notes, shown before the wearer commits to downloading. */
    val notes: String = "",
)

/**
 * Reads published releases from a GitHub repository.
 *
 * Replaces the previous Google Drive folder, which had two problems worth naming. It was
 * scraped from `embeddedfolderview` — undocumented HTML that Google can change at any
 * time — and a Drive folder has no notion of a *release*: any APK dropped in it, however
 * experimental, immediately looked like an update to every watch. GitHub Releases is a
 * documented JSON API where publishing is a deliberate act, and it is where the source
 * already lives.
 *
 * No credentials. The releases endpoint is public for public repositories; unauthenticated
 * callers get 60 requests per hour per IP, which is ample for a watch that checks
 * occasionally. Pre-releases and drafts are skipped so a test build can be staged without
 * every watch offering it.
 */
class GitHubReleaseClient(
    private val owner: String,
    private val repo: String,
) {
    private companion object {
        const val TAG = "GitHubReleaseClient"
        const val TIMEOUT_MS = 20_000
        const val USER_AGENT = "Watchtastic-Updater"
        const val API_VERSION = "2022-11-28"

        /** Enough to find the newest stable build even after a run of pre-releases. */
        const val PAGE_SIZE = 20
    }

    /** Newest first. */
    suspend fun listApks(): List<RemoteBuild> = withContext(Dispatchers.IO) {
        val url = URL(
            "https://api.github.com/repos/$owner/$repo/releases?per_page=$PAGE_SIZE",
        )
        val releases = JSONArray(url.readText())

        buildList {
            for (i in 0 until releases.length()) {
                val release = releases.optJSONObject(i) ?: continue
                if (release.optBoolean("draft") || release.optBoolean("prerelease")) continue

                val tag = release.optString("tag_name")
                val notes = release.optString("body").orEmpty()
                val assets = release.optJSONArray("assets") ?: continue

                for (j in 0 until assets.length()) {
                    val asset = assets.optJSONObject(j) ?: continue
                    val name = asset.optString("name")
                    if (!name.endsWith(".apk", ignoreCase = true)) continue
                    val href = asset.optString("browser_download_url")
                    if (href.isBlank()) continue

                    // Prefer the version in the filename; fall back to the tag, so a
                    // release named "v1.3.0" still resolves if the asset is not.
                    val version = Version.fromFileName(name) ?: Version.parse(tag)
                    if (version == null) {
                        Log.w(TAG, "skipping asset with no parseable version: $name")
                        continue
                    }
                    add(
                        RemoteBuild(
                            downloadUrl = href,
                            fileName = name,
                            version = version,
                            notes = notes,
                        ),
                    )
                }
            }
        }.sortedByDescending { it.version }
    }

    /** Streams a release asset to [destination]. */
    suspend fun download(
        build: RemoteBuild,
        destination: File,
        onProgress: (bytes: Long, total: Long) -> Unit = { _, _ -> },
    ): File = withContext(Dispatchers.IO) {
        val connection = (URL(build.downloadUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            // Asset URLs redirect to a signed objects.githubusercontent.com link.
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", "application/octet-stream")
        }

        try {
            if (connection.responseCode !in 200..299) {
                throw IOException("GitHub returned HTTP ${connection.responseCode}")
            }
            val total = connection.contentLengthLong
            destination.parentFile?.mkdirs()
            var written = 0L
            connection.inputStream.use { input ->
                destination.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        written += read
                        onProgress(written, total)
                    }
                }
            }
            // An error page would land here as a few kilobytes of HTML. Catching it now
            // gives a clear error rather than a baffling parse failure at install time.
            if (!destination.looksLikeApk()) {
                destination.delete()
                throw IOException("Download was not an APK")
            }
            destination
        } finally {
            connection.disconnect()
        }
    }

    private fun URL.readText(): String {
        val connection = (openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("X-GitHub-Api-Version", API_VERSION)
        }
        return try {
            when (connection.responseCode) {
                in 200..299 -> connection.inputStream.bufferedReader().use { it.readText() }
                // The one failure worth naming: the unauthenticated hourly quota.
                403, 429 -> throw IOException("GitHub rate limit reached — try again later")
                404 -> throw IOException("No releases found for $owner/$repo")
                else -> throw IOException("HTTP ${connection.responseCode} from GitHub")
            }
        } finally {
            connection.disconnect()
        }
    }
}

/** APKs are zips: they start with "PK". */
private fun File.looksLikeApk(): Boolean {
    if (!exists() || length() < 1024) return false
    return inputStream().use { stream ->
        val header = ByteArray(4)
        stream.read(header) == 4 &&
            header[0] == 0x50.toByte() && header[1] == 0x4B.toByte() &&
            header[2] == 0x03.toByte() && header[3] == 0x04.toByte()
    }
}
