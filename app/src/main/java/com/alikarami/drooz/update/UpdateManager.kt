package com.alikarami.drooz.update

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Where an update stands. Every state past [UpToDate] carries the release it is about. */
sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val release: Release) : UpdateState
    data class Downloading(val release: Release, val progress: Float) : UpdateState
    /** Waiting for the user to allow «نصب برنامه‌های ناشناس» for this app in the system settings. */
    data class NeedsPermission(val release: Release) : UpdateState
    /** Handed to the system installer (its confirmation screen may be showing). */
    data class Installing(val release: Release) : UpdateState
    data class Failed(val step: Step, val release: Release?) : UpdateState
    enum class Step { CHECK, DOWNLOAD, INSTALL }
}

/** The system installer's verdict on a committed session. */
sealed interface InstallResult {
    data object Succeeded : InstallResult
    /** The user declined on the confirmation screen. */
    data object Cancelled : InstallResult
    data class Failed(val message: String?) : InstallResult
}

/** Installs an APK file (PackageInstaller on the device; a fake in tests). */
interface ApkInstaller {
    /** Whether this app may install packages («نصب برنامه‌های ناشناس» is on for it). */
    fun canInstall(): Boolean
    /** Opens the system screen where the user allows it. */
    fun openInstallPermission()
    /** Commits [apk] to the system installer; the verdict arrives through [UpdateManager.onInstallResult]. */
    suspend fun install(apk: File)
}

/** When the last automatic check succeeded (epoch millis; 0 = never). */
interface CheckRecord {
    fun lastCheck(): Long
    fun setLastCheck(millis: Long)
}

class FileCheckRecord(private val file: File) : CheckRecord {
    override fun lastCheck(): Long = runCatching { file.readText().trim().toLong() }.getOrDefault(0L)
    override fun setLastCheck(millis: Long) { runCatching { file.writeText(millis.toString()) } }
}

/**
 * The app's self-update: checks GitHub for a newer release (automatically at most every [checkInterval],
 * or on demand from Settings), downloads its APK into [dir] and hands it to the system installer.
 * An automatic check that finds one raises [prompt] (Home shows it); a failed automatic check stays quiet.
 * One operation runs at a time; asking again while one is under way does nothing.
 */
class UpdateManager(
    private val current: AppVersion,
    private val source: ReleaseSource,
    private val downloader: ApkDownloader,
    private val installer: ApkInstaller,
    private val dir: File,
    private val record: CheckRecord,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    private val now: () -> Long = System::currentTimeMillis,
    private val checkInterval: Long = 12 * 60 * 60 * 1000L,
) {
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    private val _prompt = MutableStateFlow(false)
    /** True while Home should show the update dialog. */
    val prompt: StateFlow<Boolean> = _prompt.asStateFlow()

    private var job: Job? = null
    private val busy get() = job?.isActive == true

    init {
        // A downloaded APK has done its job once the app runs (it was installed, or it will be fetched again).
        scope.launch(io) { dir.listFiles()?.forEach { it.delete() } }
    }

    /**
     * From Home (each time it resumes): checks if the last successful check is older than the interval.
     * A settled state is checked again (the app may stay open for days, and a dismissed release is offered
     * again); a download, an install or a wait for the permission is left alone.
     */
    fun checkIfDue() {
        val s = _state.value
        val settled = s is UpdateState.Idle || s is UpdateState.UpToDate || s is UpdateState.Available || s is UpdateState.Failed
        if (busy || !settled) return
        val last = record.lastCheck()
        if (last in 1..now() && now() - last < checkInterval) return
        job = scope.launch {
            val release = try { source.latest() } catch (e: CancellationException) { throw e } catch (_: Exception) { return@launch }
            record.setLastCheck(now())
            if (release != null && release.version > current) {
                _state.value = UpdateState.Available(release)
                _prompt.value = true
            } else {
                _state.value = UpdateState.UpToDate
            }
        }
    }

    /** From Settings: checks now, whatever the interval. Keeps a release already found or in progress. */
    fun checkNow() {
        if (busy) return
        val s = _state.value
        if (s !is UpdateState.Idle && s !is UpdateState.UpToDate && !(s is UpdateState.Failed && s.release == null)) return
        _state.value = UpdateState.Checking
        job = scope.launch {
            _state.value = try {
                val release = source.latest()
                record.setLastCheck(now())
                if (release != null && release.version > current) UpdateState.Available(release) else UpdateState.UpToDate
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                UpdateState.Failed(UpdateState.Step.CHECK, null)
            }
        }
    }

    /**
     * Downloads and installs the release found; first sends the user to allow installs if they must. Also
     * retries from [UpdateState.Installing], in case the system's confirmation screen was left without a verdict.
     */
    fun install() {
        if (busy) return
        val release = releaseOf(_state.value) ?: return
        if (!installer.canInstall()) {
            _state.value = UpdateState.NeedsPermission(release)
            installer.openInstallPermission()
            return
        }
        _state.value = UpdateState.Downloading(release, 0f)
        job = scope.launch {
            val apk = File(dir, "DRooz-${release.version}.apk")
            try {
                withContext(io) { dir.listFiles()?.forEach { it.delete() } }
                downloader.download(release, apk) { p ->
                    _state.update { if (it is UpdateState.Downloading) it.copy(progress = p) else it }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _state.value = UpdateState.Failed(UpdateState.Step.DOWNLOAD, release)
                return@launch
            }
            _state.value = UpdateState.Installing(release)
            try {
                installer.install(apk)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _state.value = UpdateState.Failed(UpdateState.Step.INSTALL, release)
            }
        }
    }

    /** Opens the install permission screen again (the user backed out of it). */
    fun openInstallPermission() = installer.openInstallPermission()

    /** The app came back to the foreground: continues an install that was waiting for the permission. */
    fun onResume() {
        if (_state.value is UpdateState.NeedsPermission && installer.canInstall()) install()
    }

    fun onInstallResult(result: InstallResult) {
        val release = (_state.value as? UpdateState.Installing)?.release ?: return
        _state.value = when (result) {
            // The process is normally replaced before this arrives.
            InstallResult.Succeeded -> UpdateState.UpToDate
            InstallResult.Cancelled -> UpdateState.Available(release)
            is InstallResult.Failed -> UpdateState.Failed(UpdateState.Step.INSTALL, release)
        }
    }

    /** «بعداً»: closes Home's dialog; the next automatic check (or Settings) offers it again. */
    fun dismissPrompt() { _prompt.value = false }

    private fun releaseOf(s: UpdateState): Release? = when (s) {
        is UpdateState.Available -> s.release
        is UpdateState.NeedsPermission -> s.release
        is UpdateState.Failed -> s.release
        is UpdateState.Installing -> s.release
        else -> null
    }
}
