package com.alikarami.drooz.ui.common

import com.alikarami.drooz.site.SiteException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation

class StatesTest {
    @Test fun safeLaunchRethrowsCancellation() = runTest {
        var errors = 0
        val started = CompletableDeferred<Unit>()
        val job = safeLaunch(onError = { errors++ }) {
            started.complete(Unit)
            awaitCancellation()
        }
        started.await()
        job.cancel()
        job.join()
        assertEquals(0, errors)
        assertTrue(job.isCancelled)
    }

    @Test fun safeLaunchReportsErrors() = runTest {
        var caught: Throwable? = null
        safeLaunch(onError = { caught = it }) { throw SiteException.HttpError(500) }.join()
        assertTrue(caught is SiteException.HttpError)
        assertEquals(500, (caught as SiteException.HttpError).code)
    }
}
