package com.alikarami.drooz.update

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class UpdateManagerTest {
    @get:Rule val tmp = TemporaryFolder()

    private val current = AppVersion(1, 2, 3)
    private val newer = Release(AppVersion(1, 2, 4), "notes", "https://x/DRooz.apk", null, 10)

    private class FakeSource(var result: () -> Release?) : ReleaseSource {
        var calls = 0
        override suspend fun latest(): Release? { calls++; return result() }
    }

    private class FakeDownloader : ApkDownloader {
        var fail = false
        var gate: CompletableDeferred<Unit>? = null
        override suspend fun download(release: Release, target: File, onProgress: (Float) -> Unit) {
            onProgress(0.5f)
            gate?.await()
            if (fail) throw IOException("boom")
            target.parentFile?.mkdirs()
            target.writeText("apk")
        }
    }

    private class FakeInstaller : ApkInstaller {
        var allowed = true
        var permissionOpened = 0
        val installed = mutableListOf<File>()
        var fail = false
        override fun canInstall() = allowed
        override fun openInstallPermission() { permissionOpened++ }
        override suspend fun install(apk: File) { if (fail) throw IOException("x"); installed += apk }
    }

    private class MemRecord(var value: Long = 0) : CheckRecord {
        override fun lastCheck() = value
        override fun setLastCheck(millis: Long) { value = millis }
    }

    private var clock = 100_000_000L
    private val source = FakeSource { newer }
    private val downloader = FakeDownloader()
    private val installer = FakeInstaller()
    private val record = MemRecord()

    private fun TestScope.manager(): UpdateManager {
        val d = StandardTestDispatcher(testScheduler)
        return UpdateManager(current, source, downloader, installer, tmp.root.resolve("update"), record,
            this, d, now = { clock }, checkInterval = 1000)
    }

    @Test fun autoCheckFindsNewerAndPrompts() = runTest {
        val m = manager()
        m.checkIfDue(); runCurrent()
        assertEquals(UpdateState.Available(newer), m.state.value)
        assertTrue(m.prompt.value)
        assertEquals(clock, record.value)
        m.dismissPrompt()
        assertFalse(m.prompt.value)
        assertEquals(UpdateState.Available(newer), m.state.value)
    }

    @Test fun autoCheckRespectsTheInterval() = runTest {
        record.value = clock - 500
        val m = manager()
        m.checkIfDue(); runCurrent()
        assertEquals(0, source.calls)
        clock += 600
        m.checkIfDue(); runCurrent()
        assertEquals(1, source.calls)
    }

    @Test fun longRunningAppChecksAgainOnceDue() = runTest {
        source.result = { newer.copy(version = current) }
        val m = manager()
        m.checkIfDue(); runCurrent()
        assertEquals(UpdateState.UpToDate, m.state.value)
        // A release published while the app stays open.
        source.result = { newer }
        clock += 500
        m.checkIfDue(); runCurrent()
        assertEquals("not due yet", UpdateState.UpToDate, m.state.value)
        clock += 600
        m.checkIfDue(); runCurrent()
        assertEquals(UpdateState.Available(newer), m.state.value)
        assertTrue(m.prompt.value)
    }

    @Test fun dismissedReleaseIsOfferedAgainOnceDue() = runTest {
        val m = manager()
        m.checkIfDue(); runCurrent()
        m.dismissPrompt()
        m.checkIfDue(); runCurrent()
        assertFalse("not due yet", m.prompt.value)
        clock += 1001
        m.checkIfDue(); runCurrent()
        assertTrue(m.prompt.value)
    }

    @Test fun dueCheckLeavesAnUpdateInProgressAlone() = runTest {
        installer.allowed = false
        val m = manager()
        m.checkIfDue(); runCurrent()
        m.install(); runCurrent()
        clock += 1001
        m.checkIfDue(); runCurrent()
        assertEquals(UpdateState.NeedsPermission(newer), m.state.value)
        assertEquals(1, source.calls)
    }

    @Test fun sameOrOlderIsUpToDateAndSilent() = runTest {
        source.result = { newer.copy(version = current) }
        val m = manager()
        m.checkIfDue(); runCurrent()
        assertEquals(UpdateState.UpToDate, m.state.value)
        assertFalse(m.prompt.value)
    }

    @Test fun failedAutoCheckIsQuietAndNotRecorded() = runTest {
        source.result = { throw IOException("offline") }
        val m = manager()
        m.checkIfDue(); runCurrent()
        assertEquals(UpdateState.Idle, m.state.value)
        assertEquals(0L, record.value)
    }

    @Test fun manualCheckReportsFailureAndCanRetry() = runTest {
        source.result = { throw IOException("offline") }
        val m = manager()
        m.checkNow(); runCurrent()
        assertEquals(UpdateState.Failed(UpdateState.Step.CHECK, null), m.state.value)
        source.result = { newer }
        m.checkNow(); runCurrent()
        assertEquals(UpdateState.Available(newer), m.state.value)
        assertFalse("a manual check doesn't pop the Home dialog", m.prompt.value)
    }

    @Test fun installDownloadsThenHandsToInstaller() = runTest {
        val m = manager()
        m.checkNow(); runCurrent()
        downloader.gate = CompletableDeferred()
        m.install(); runCurrent()
        assertEquals(UpdateState.Downloading(newer, 0.5f), m.state.value)
        downloader.gate!!.complete(Unit); runCurrent()
        assertEquals(UpdateState.Installing(newer), m.state.value)
        assertEquals("DRooz-1.2.4.apk", installer.installed.single().name)
        m.onInstallResult(InstallResult.Cancelled)
        assertEquals(UpdateState.Available(newer), m.state.value)
    }

    @Test fun withoutPermissionAsksThenContinuesOnResume() = runTest {
        installer.allowed = false
        val m = manager()
        m.checkNow(); runCurrent()
        m.install(); runCurrent()
        assertEquals(UpdateState.NeedsPermission(newer), m.state.value)
        assertEquals(1, installer.permissionOpened)
        m.onResume(); runCurrent()
        assertEquals("still not allowed: still waiting", UpdateState.NeedsPermission(newer), m.state.value)
        installer.allowed = true
        m.onResume(); runCurrent()
        assertEquals(UpdateState.Installing(newer), m.state.value)
        assertEquals(1, installer.installed.size)
    }

    @Test fun downloadFailureCanBeRetried() = runTest {
        val m = manager()
        m.checkNow(); runCurrent()
        downloader.fail = true
        m.install(); runCurrent()
        assertEquals(UpdateState.Failed(UpdateState.Step.DOWNLOAD, newer), m.state.value)
        downloader.fail = false
        m.install(); runCurrent()
        assertEquals(UpdateState.Installing(newer), m.state.value)
    }

    @Test fun installerFailureReported() = runTest {
        val m = manager()
        m.checkNow(); runCurrent()
        m.install(); runCurrent()
        m.onInstallResult(InstallResult.Failed("sig"))
        assertEquals(UpdateState.Failed(UpdateState.Step.INSTALL, newer), m.state.value)
    }

    @Test fun leftoverApksAreDeletedAtStart() = runTest {
        val old = tmp.root.resolve("update/DRooz-1.2.3.apk").apply { parentFile!!.mkdirs(); writeText("old") }
        manager(); runCurrent()
        assertFalse(old.exists())
    }
}
