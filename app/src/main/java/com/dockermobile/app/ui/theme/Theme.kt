package com.dockermobile.app.ui.theme

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.provider.Settings
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/**
 * The tokens Material's own [androidx.compose.material3.ColorScheme] has no
 * slot for: the second label level, hairline separators, control fills and the
 * four container/VM states.
 */
@Immutable
data class AppleColors(
    val labelSecondary: Color,
    val labelTertiary: Color,
    val separator: Color,
    val fill: Color,
    val knob: Color,
    val base: Color,
    val elevated: Color,
    val elevated2: Color,
    val statusRunning: Color,
    val statusWarn: Color,
    val statusError: Color,
    val statusNeutral: Color,
    val isDark: Boolean,
)

private val LightAppleColors = AppleColors(
    labelSecondary = LabelSecondaryLight,
    labelTertiary = LabelTertiaryLight,
    separator = SeparatorLight,
    fill = FillLight,
    knob = KnobLight,
    base = BaseLight,
    elevated = ElevatedLight,
    elevated2 = Elevated2Light,
    statusRunning = StatusRunningLight,
    statusWarn = StatusWarnLight,
    statusError = StatusErrorLight,
    statusNeutral = StatusNeutralLight,
    isDark = false,
)

private val DarkAppleColors = AppleColors(
    labelSecondary = LabelSecondaryDark,
    labelTertiary = LabelTertiaryDark,
    separator = SeparatorDark,
    fill = FillDark,
    knob = KnobDark,
    base = BaseDark,
    elevated = ElevatedDark,
    elevated2 = Elevated2Dark,
    statusRunning = StatusRunningDark,
    statusWarn = StatusWarnDark,
    statusError = StatusErrorDark,
    statusNeutral = StatusNeutralDark,
    isDark = true,
)

val LocalAppleColors = compositionLocalOf { DarkAppleColors }

/** Shorthand: `AppTheme.colors.separator`. */
object AppTheme {
    val colors: AppleColors
        @Composable get() = LocalAppleColors.current
}

private val DarkScheme = darkColorScheme(
    primary = AccentDark,
    onPrimary = Color.Black,
    primaryContainer = AccentDark.copy(alpha = 0.18f),
    onPrimaryContainer = AccentDark,
    secondary = AccentDark,
    onSecondary = Color.Black,
    background = BaseDark,
    onBackground = LabelDark,
    surface = ElevatedDark,
    onSurface = LabelDark,
    surfaceVariant = Elevated2Dark,
    onSurfaceVariant = Color(0xFFB9B9C0), // opaque form of the secondary label
    surfaceContainerHigh = Elevated2Dark,
    outline = SeparatorDark,
    outlineVariant = SeparatorDark,
    error = StatusErrorDark,
    onError = Color.Black,
    errorContainer = StatusErrorDark.copy(alpha = 0.16f),
    onErrorContainer = StatusErrorDark,
    tertiary = StatusRunningDark,
    scrim = Color.Black.copy(alpha = 0.4f),
)

private val LightScheme = lightColorScheme(
    primary = AccentLight,
    onPrimary = OnAccent,
    primaryContainer = AccentLight.copy(alpha = 0.12f),
    onPrimaryContainer = AccentLight,
    secondary = AccentLight,
    onSecondary = OnAccent,
    background = BaseLight,
    onBackground = LabelLight,
    surface = ElevatedLight,
    onSurface = LabelLight,
    surfaceVariant = Elevated2Light,
    onSurfaceVariant = Color(0xFF6C6C70),
    surfaceContainerHigh = Elevated2Light,
    outline = SeparatorLight,
    outlineVariant = SeparatorLight,
    error = StatusErrorLight,
    onError = OnAccent,
    errorContainer = StatusErrorLight.copy(alpha = 0.10f),
    onErrorContainer = StatusErrorLight,
    tertiary = StatusRunningLight,
    scrim = Color.Black.copy(alpha = 0.32f),
)

/**
 * Follows the system appearance in both directions. The HIG is explicit that an
 * app shouldn't ship its own appearance switch — people expect their systemwide
 * choice to be respected — so there is no in-app dark/light toggle.
 */
@Composable
fun DockerMobileTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = view.context.findActivity()?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).apply {
                // Dark content on light chrome, and the reverse.
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    CompositionLocalProvider(
        LocalAppleColors provides if (darkTheme) DarkAppleColors else LightAppleColors,
    ) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkScheme else LightScheme,
            typography = AppleTypography,
            shapes = AppleShapes,
            content = content,
        )
    }
}

private fun Context.findActivity(): Activity? {
    var ctx = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}

/**
 * True when the device asks for reduced motion (all animation scales at zero).
 * Callers drop transitions rather than shortening them.
 */
@Composable
fun reduceMotion(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        runCatching {
            Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.TRANSITION_ANIMATION_SCALE,
                1f,
            ) == 0f
        }.getOrDefault(false)
    }
}
