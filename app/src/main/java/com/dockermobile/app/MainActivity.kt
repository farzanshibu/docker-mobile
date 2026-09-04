package com.dockermobile.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import com.dockermobile.app.core.AppGraph
import com.dockermobile.app.core.LocalGraph
import com.dockermobile.app.ui.AppRoot
import com.dockermobile.app.ui.theme.DockerMobileTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val graph = (application as DockMobileApp).graph
        setContent {
            CompositionLocalProvider(LocalGraph provides graph) {
                DockerMobileTheme {
                    AppRoot()
                }
            }
        }
    }
}
