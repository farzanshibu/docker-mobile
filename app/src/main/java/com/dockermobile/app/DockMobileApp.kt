package com.dockermobile.app

import android.app.Application
import com.dockermobile.app.core.AppGraph

/**
 * Application entry point. Owns the single [AppGraph] (tiny hand-rolled DI
 * container) for the whole process lifetime.
 */
class DockMobileApp : Application() {

    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
    }
}
