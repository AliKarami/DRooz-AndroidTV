package com.alikarami.drooz.data

import android.os.SystemClock
import com.alikarami.drooz.site.model.HomePage

/**
 * The last home page fetched in this process. A home recreated soon after (a warm start: Back out,
 * launch again) shows it at once instead of a skeleton while the page downloads and parses (~2 s on TV).
 * Within [ttlMs] the page is treated as current, just as a home that was never left keeps its rows.
 */
class HomePageCache(
    private val ttlMs: Long = 10 * 60_000L,
    private val clock: () -> Long = SystemClock::elapsedRealtime,
) {
    private var page: HomePage? = null
    private var at = 0L

    @Synchronized fun fresh(): HomePage? = page?.takeIf { clock() - at < ttlMs }

    @Synchronized fun put(page: HomePage) {
        this.page = page
        at = clock()
    }
}
