package com.dockermobile.app.core

import androidx.compose.runtime.staticCompositionLocalOf

/** CompositionLocal giving every composable access to the AppGraph. */
val LocalGraph = staticCompositionLocalOf<AppGraph> {
    error("AppGraph not provided")
}
