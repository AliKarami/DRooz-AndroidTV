package com.alikarami.drooz

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader

class DroozApp : Application(), SingletonImageLoader.Factory {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
    }

    /** Coil's singleton is the loader built once in [AppGraph]. */
    override fun newImageLoader(context: PlatformContext): ImageLoader = graph.imageLoader
}
