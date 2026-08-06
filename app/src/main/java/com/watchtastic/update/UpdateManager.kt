package com.watchtastic.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.util.Log
import com.watchtastic.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/** Where the updater currently is, for the UI to render. */
sealed interface UpdateState {
    data object Idle : UpdateState

    data object Checking : UpdateState

    data class UpToDate(val current: String, val checkedAtMs: Long) : UpdateState

    data class Available(val build: RemoteBuild) : UpdateState

    data class Downloading(val build: RemoteBuild, val progress: Float) : UpdateState

    /** Downloaded and signature-checked; waiting on the system install prompt. */
    data class ReadyToInstall(val build: RemoteBuild) : UpdateState

    data object Installing : UpdateState

    data class Failed(val reason: String) : UpdateState
}

/**
 * Self-update from the project's published GitHub releases.
 *
 * The security model matters more than the plumbing here, because "download an APK and
 * install it" is otherwise a remote code execution feature:
 *
 *  1. **Signature pinning.** A downloaded APK is only offered to the installer if its
 *     signing certificate is byte-identical to the one the running app was signed with.
 *     Anyone who could put a file in the folder still cannot make this app install code
 *     they signed — they'd need the private key.
 *  2. **Package pinning.** The APK must declare our own package name.
 *  3. **Never silent.** Installation goes through [PackageInstaller], so Android — not
 *     this app — asks the wearer to confirm. There is no path here that installs
 *     anything without an explicit yes.
 *
 * Downgrades are refused too: an older published release can't be used to roll a device
 * back onto a build with known holes.
 */
class UpdateManager(
    private val context: Context,
    private val client: GitHubReleaseClient,
    /**
     * Application-scoped, deliberately. Update work must not be tied to the screen that
     * started it: the button that triggers a check lives in a `when(state)` branch, so
     * the moment state flips to Checking that branch — and any composable scope it
     * owned — leaves the composition. Work launched there would cancel itself a
     * millisecond after starting.
     */
    private val scope: CoroutineScope,
) {
    companion object {
        private const val TAG = "UpdateManager"
        const val ACTION_INSTALL_STATUS = "com.watchtastic.action.INSTALL_STATUS"
        private const val APK_NAME = "update.apk"
    }

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    val currentVersion: Version =
        Version.parse(BuildConfig.VERSION_NAME) ?: Version(listOf(0), BuildConfig.VERSION_NAME)

    private val stagedApk: File get() = File(context.cacheDir, APK_NAME)

    init {
        // A successful install replaces this process, so the cleanup in
        // onInstallSucceeded() never gets to run and a several-megabyte APK is left in
        // the cache. Clearing at startup collects it: any staged file is orphaned by
        // definition, because the "ready to install" state only lives in memory.
        runCatching { if (stagedApk.exists()) stagedApk.delete() }
    }

    /** True when the wearer has allowed this app to install packages. */
    val canInstall: Boolean
        get() = context.packageManager.canRequestPackageInstalls()

    @Volatile
    private var activeJob: Job? = null

    /**
     * Runs one update operation at a time on the application scope.
     *
     * The UI calls these rather than launching the suspend functions itself, so no screen
     * can accidentally own the lifetime of a download again. The single-flight guard also
     * makes a double-tap harmless.
     */
    private fun launchExclusive(block: suspend () -> Unit) {
        if (activeJob?.isActive == true) return
        activeJob = scope.launch { block() }
    }

    fun checkNow() = launchExclusive { check() }

    fun downloadNow(build: RemoteBuild) = launchExclusive { download(build) }

    fun installNow() = launchExclusive { install() }

    /** Looks for a newer build. Safe to call repeatedly. */
    suspend fun check(): UpdateState {
        _state.value = UpdateState.Checking
        val result = runCatching { client.listApks() }
        val builds = result.getOrElse { error ->
            error.rethrowIfCancellation()
            Log.w(TAG, "update check failed", error)
            return UpdateState.Failed(friendlyError(error)).also { _state.value = it }
        }

        val newest = builds.firstOrNull()
        val next = when {
            newest == null -> UpdateState.UpToDate(
                currentVersion.raw,
                System.currentTimeMillis(),
            )

            newest.version > currentVersion -> UpdateState.Available(newest)

            else -> UpdateState.UpToDate(currentVersion.raw, System.currentTimeMillis())
        }
        _state.value = next
        return next
    }

    /** Downloads [build] and verifies it before anything is handed to the installer. */
    suspend fun download(build: RemoteBuild): UpdateState {
        _state.value = UpdateState.Downloading(build, 0f)
        val result = runCatching {
            val file = client.download(build, stagedApk) { written, total ->
                if (total > 0) {
                    _state.value = UpdateState.Downloading(build, written.toFloat() / total)
                }
            }
            verify(file)
            file
        }

        return result.fold(
            onSuccess = { UpdateState.ReadyToInstall(build).also { _state.value = it } },
            onFailure = { error ->
                error.rethrowIfCancellation()
                Log.w(TAG, "download/verify failed", error)
                runCatching { stagedApk.delete() }
                UpdateState.Failed(friendlyError(error)).also { _state.value = it }
            },
        )
    }

    /**
     * Hands the staged APK to the system installer, which prompts the wearer.
     *
     * We stream the bytes into a [PackageInstaller] session rather than firing a
     * `content://` VIEW intent: no FileProvider, no world-readable copy of the APK, and
     * it is the route Android actually supports going forward.
     */
    suspend fun install(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val apk = stagedApk
            require(apk.exists()) { "Nothing downloaded yet" }
            verify(apk)

            _state.value = UpdateState.Installing
            val installer = context.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL,
            ).apply {
                setAppPackageName(context.packageName)
            }

            val sessionId = installer.createSession(params)
            installer.openSession(sessionId).use { session ->
                session.openWrite("watchtastic", 0, apk.length()).use { output ->
                    apk.inputStream().use { input -> input.copyTo(output) }
                    session.fsync(output)
                }
                val intent = Intent(ACTION_INSTALL_STATUS).setPackage(context.packageName)
                val pending = PendingIntent.getBroadcast(
                    context,
                    sessionId,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
                )
                session.commit(pending.intentSender)
            }
            Unit
        }.onFailure { error ->
            error.rethrowIfCancellation()
            Log.w(TAG, "install failed", error)
            _state.value = UpdateState.Failed(friendlyError(error))
        }
    }

    fun onInstallFailed(reason: String) {
        _state.value = UpdateState.Failed(reason)
    }

    fun onInstallSucceeded() {
        runCatching { stagedApk.delete() }
        _state.value = UpdateState.Idle
    }

    fun reset() {
        _state.value = UpdateState.Idle
    }

    // -------------------------------------------------------------- verification

    private fun verify(apk: File) {
        val pm = context.packageManager
        val remote = pm.getPackageArchiveInfo(
            apk.absolutePath,
            PackageManager.GET_SIGNING_CERTIFICATES,
        ) ?: throw SecurityException("Could not read the downloaded package")

        if (remote.packageName != context.packageName) {
            throw SecurityException("That APK is ${remote.packageName}, not this app")
        }

        val remoteVersion = Version.parse(remote.versionName.orEmpty())
        if (remoteVersion != null && remoteVersion < currentVersion) {
            throw SecurityException("That build ($remoteVersion) is older than this one")
        }

        val mine = pm.getPackageInfo(
            context.packageName,
            PackageManager.GET_SIGNING_CERTIFICATES,
        )
        val remoteCerts = remote.signingInfo?.fingerprints()
        val myCerts = mine.signingInfo?.fingerprints()

        if (remoteCerts.isNullOrEmpty() || myCerts.isNullOrEmpty()) {
            throw SecurityException("Could not read signing certificates")
        }
        if (remoteCerts != myCerts) {
            // The headline safety property: a build signed by anyone else is refused,
            // no matter who put it in the folder.
            throw SecurityException("That build was signed with a different key")
        }
    }

    private fun android.content.pm.SigningInfo.fingerprints(): Set<String> {
        val signatures: Array<Signature> = if (hasMultipleSigners()) {
            apkContentsSigners
        } else {
            signingCertificateHistory
        }
        return signatures.map { it.sha256() }.toSet()
    }

    private fun Signature.sha256(): String =
        MessageDigest.getInstance("SHA-256")
            .digest(toByteArray())
            .joinToString("") { "%02x".format(it) }

    private fun friendlyError(error: Throwable): String = when (error) {
        is SecurityException -> error.message ?: "Update rejected"
        is java.net.UnknownHostException -> "No network"
        is java.net.SocketTimeoutException -> "GitHub timed out"
        else -> error.message ?: "Update failed"
    }

    /**
     * Cancellation is not a failure and must never reach the screen.
     *
     * `runCatching` catches [CancellationException] like anything else, which is how the
     * literal text "The coroutine scope left the composition" ended up rendered in red as
     * though it were an update error. Rethrowing also keeps structured concurrency
     * honest: a cancelled parent stays cancelled.
     */
    private fun Throwable.rethrowIfCancellation() {
        if (this is CancellationException) throw this
    }
}
