package com.dockermobile.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val DarkColors = darkColorScheme(
    primary = DockerBlue,
    onPrimary = androidx.compose.ui.graphics.Color.White,
    primaryContainer = DockerBlueContainer,
    onPrimaryContainer = DockerBlueDim,
    secondary = DockerBlueDim,
    onSecondary = NightBg,
    background = NightBg,
    onBackground = NightText,
    surface = NightSurface,
    onSurface = NightText,
    surfaceVariant = NightSurfaceVariant,
    onSurfaceVariant = NightTextDim,
    outline = NightOutline,
    error = StatusRed,
    tertiary = StatusGreen,
)

private val LightColors = lightColorScheme(
    primary = DockerBlue,
    onPrimary = androidx.compose.ui.graphics.Color.White,
)

/**
 * The app is Docker-dark first (as chosen in project setup); light follows the
 * system only for the remote-only mode where the VM is not used. Kept simple:
 * always dark, matching Docker Desktop's dark theme.
 */
@Composable
fun DockerMobileTheme(content: @Composable () -> Unit) {
    @Suppress("UNUSED_EXPRESSION")
    isSystemInDarkTheme() // noted for future light support; v1 is dark-only
    MaterialTheme(
        colorScheme = DarkColors,
        content = content,
    )
}
