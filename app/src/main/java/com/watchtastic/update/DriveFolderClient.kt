package com.watchtastic.update

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** One APK sitting in the update folder. */
data class RemoteBuild(
    val fileId: String,
    val fileName: String,
    val version: Version,
)

/**
 * Reads the shared Google Drive folder that holds released APKs.
 *
 * Two ways in, because they trade off differently:
 *
 *  - **Drive API v3**, used when an API key is configured. Documented, stable JSON.
 *  - **`embeddedfolderview`**, the keyless fallback. It is the endpoint Drive itself uses
 *    to embed a public folder listing in a web page, so it needs no credentials at all —
 *    but it returns HTML, and Google can change that markup whenever they like.
 *
 * Starting keyless means updates work the moment a build is dropped in the folder;
 * adding a key later upgrades the path without changing anything else.
 */
class DriveFolderClient(
    private val folderId: String,
    private val apiKey: String? = null,
) {
    private companion object {
        const val TAG = "DriveFolderClient"
        const val TIMEOUT_MS = 20_000
        const val USER_AGENT = "Watchtastic-Updater"
    }

    val usingApiKey: Boolean get() = !apiKey.isNullOrBlank()

    suspend fun listApks(): List<RemoteBuild> = withContext(Dispatchers.IO) {
        val builds = if (usingApiKey) {
            runCatching { listViaApi() }
                .onFailure { Log.w(TAG, "Drive API listing failed, falling back", it) }
                .getOrElse { listViaEmbeddedView() }
        } else {
            listViaEmbeddedView()
        }
        builds
            .filter { it.fileName.endsWith(".apk", ignoreCase = true) }
            .sortedByDescending { it.version }
    }

    private fun listViaApi(): List<RemoteBuild> {
        val url = URL(
            "https://www.googleapis.com/drive/v3/files" +
                "?q=" + encode("'$folderId' in parents and trashed = false") +
                "&fields=" + encode("files(id,name)") +
                "&pageSize=100" +
                "&key=$apiKey",
        )
        val body = url.readText()
        val files = JSONObject(body).optJSONArray("files") ?: return emptyList()
        return (0 until files.length()).mapNotNull { index ->
            val entry = files.getJSONObject(index)
            buildFrom(entry.optString("id"), entry.optString("name"))
        }
    }

    /**
     * Scrapes the public embed listing. Each row carries the file id on the container
     * div and the filename in a `flip-entry-title` div.
     */
    private fun listViaEmbeddedView(): List<RemoteBuild> {
        val html = URL("https://drive.google.com/embeddedfolderview?id=$folderId#list").readText()
        val entryPattern =
            Regex("""id="entry-([^"]+)"[\s\S]*?flip-entry-title">([^<]+)<""")
        return entryPattern.findAll(html).mapNotNull { match ->
            buildFrom(match.groupValues[1], match.groupValues[2].trim())
        }.toList()
    }

    private fun buildFrom(fileId: String, fileName: String): RemoteBuild? {
        if (fileId.isBlank() || fileName.isBlank()) return null
        val version = Version.fromFileName(fileName) ?: return null
        return RemoteBuild(fileId = fileId, fileName = fileName, version = version)
    }

    /**
     * Streams a file to [destination].
     *
     * Uses `drive.usercontent.google.com`, which serves the bytes directly. The older
     * `uc?export=download` host now answers with a virus-scan interstitial — an HTML page
     * that would otherwise be saved as a very broken "APK".
     */
    suspend fun download(
        build: RemoteBuild,
        destination: File,
        onProgress: (bytes: Long, total: Long) -> Unit = { _, _ -> },
    ): File = withContext(Dispatchers.IO) {
        val url = URL(
            "https://drive.usercontent.google.com/download" +
                "?id=${build.fileId}&export=download&confirm=t",
        )
        val connection = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", USER_AGENT)
        }

        try {
            if (connection.responseCode !in 200..299) {
                throw IOException("Drive returned HTTP ${connection.responseCode}")
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
            // An interstitial would land here as a few kilobytes of HTML. Catching it now
            // gives a clear error instead of a baffling parse failure at install time.
            if (!destination.looksLikeApk()) {
                destination.delete()
                throw IOException("Download was not an APK — is the file still shared?")
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
        }
        return try {
            if (connection.responseCode !in 200..299) {
                throw IOException("HTTP ${connection.responseCode} from Drive")
            }
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun encode(value: String): String =
        java.net.URLEncoder.encode(value, Charsets.UTF_8.name())
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
