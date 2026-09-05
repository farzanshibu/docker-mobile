package com.dockermobile.app.ui.components

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import com.dockermobile.app.ui.theme.AppTheme

/** The smallest comfortable control, per the accessibility guidelines. */
val MinTouchTarget = 44.dp

// --------------------------------------------------------------- haptics

/**
 * Feedback delivered through touch as well as through colour and text, so an
 * action lands even when someone isn't looking at the screen.
 */
class Haptics(private val view: View) {
    fun selection() = fire(HapticFeedbackConstants.KEYBOARD_TAP)

    fun success() =
        if (Build.VERSION.SDK_INT >= 30) fire(HapticFeedbackConstants.CONFIRM)
        else fire(HapticFeedbackConstants.KEYBOARD_TAP)

    fun warning() =
        if (Build.VERSION.SDK_INT >= 30) fire(HapticFeedbackConstants.REJECT)
        else fire(HapticFeedbackConstants.LONG_PRESS)

    private fun fire(constant: Int) {
        view.performHapticFeedback(constant, HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING)
    }
}

@Composable
fun rememberHaptics(): Haptics {
    val view = LocalView.current
    return remember(view) { Haptics(view) }
}

// ------------------------------------------------------------- separators

/** Hairline rule, inset to line up with row text the way grouped lists do. */
@Composable
fun Hairline(startIndent: androidx.compose.ui.unit.Dp = 16.dp) {
    Box(
        Modifier
            .padding(start = startIndent)
            .fillMaxWidth()
            .height(1.dp)
            .background(AppTheme.colors.separator.copy(alpha = 0.6f))
    )
}

// ----------------------------------------------------------- grouped list

/**
 * An inset grouped section: a quiet uppercase-free header, a rounded card on
 * the elevated plane, and an optional footer that explains the section instead
 * of burying the explanation in a row.
 */
@Composable
fun InsetGroup(
    modifier: Modifier = Modifier,
    header: String? = null,
    footer: String? = null,
    content: @Composable () -> Unit,
) {
    Column(modifier.fillMaxWidth()) {
        if (header != null) {
            Text(
                header,
                style = MaterialTheme.typography.titleSmall,
                color = AppTheme.colors.labelSecondary,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 7.dp),
            )
        }
        Surface(
            color = AppTheme.colors.elevated,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column { content() }
        }
        if (footer != null) {
            Text(
                footer,
                style = MaterialTheme.typography.labelMedium,
                color = AppTheme.colors.labelSecondary,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 7.dp),
            )
        }
    }
}

/** Free-form content inside a grouped card, with the standard row insets. */
@Composable
fun GroupBody(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Column(modifier.padding(horizontal = 16.dp, vertical = 12.dp)) { content() }
}

/**
 * One row of a grouped list. Tappable rows get a chevron so the affordance
 * isn't carried by colour alone, and every row clears the 44pt target.
 */
@Composable
fun GroupRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    showChevron: Boolean = onClick != null,
) {
    Row(
        modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .defaultMinSize(minHeight = MinTouchTarget)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.labelMedium,
                    color = AppTheme.colors.labelSecondary,
                )
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(12.dp))
            trailing()
        }
        if (showChevron) {
            Spacer(Modifier.width(6.dp))
            Text(
                "›",
                style = MaterialTheme.typography.headlineSmall,
                color = AppTheme.colors.labelTertiary,
            )
        }
    }
}

// ---------------------------------------------------------------- buttons

/** Prominent action: filled, full-bleed, 50pt tall. */
@Composable
fun FilledAction(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(12.dp),
        modifier = modifier.height(50.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
        ),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(label, style = MaterialTheme.typography.labelLarge)
    }
}

/** Secondary action: tinted outline, same metrics as the filled one. */
@Composable
fun OutlineAction(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    destructive: Boolean = false,
) {
    val tint = if (destructive) AppTheme.colors.statusError else MaterialTheme.colorScheme.primary
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(12.dp),
        // A disabled control shouldn't keep signalling "destructive" in red.
        border = BorderStroke(
            1.dp,
            if (enabled) tint.copy(alpha = 0.45f) else AppTheme.colors.separator,
        ),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = tint),
        modifier = modifier.height(50.dp),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(label, style = MaterialTheme.typography.labelLarge)
    }
}

/**
 * A compact pill (port mapping, shell shortcut, preset). The pill is drawn at
 * 30dp but the tappable box is padded out to the 44pt minimum.
 */
@Composable
fun TapTag(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color? = null,
    icon: ImageVector? = null,
) {
    val color = tint ?: MaterialTheme.colorScheme.primary
    Box(
        modifier
            .defaultMinSize(minHeight = MinTouchTarget)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier
                .background(color.copy(alpha = 0.14f), CircleShape)
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                Icon(icon, contentDescription = null, Modifier.size(16.dp), tint = color)
                Spacer(Modifier.width(6.dp))
            }
            Text(label, style = MaterialTheme.typography.labelMedium, color = color)
        }
    }
}

/** Switch styled like the platform toggle: white knob on a tinted track. */
@Composable
fun appleSwitchColors() = SwitchDefaults.colors(
    checkedThumbColor = Color.White,
    checkedTrackColor = MaterialTheme.colorScheme.primary,
    checkedBorderColor = Color.Transparent,
    uncheckedThumbColor = Color.White,
    uncheckedTrackColor = AppTheme.colors.fill,
    uncheckedBorderColor = AppTheme.colors.separator,
)

/**
 * Slider with a round raised knob on a thin continuous track, instead of the
 * segmented Material track with tick marks.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppleSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    modifier: Modifier = Modifier,
    steps: Int = 0,
) {
    val accent = MaterialTheme.colorScheme.primary
    val trough = AppTheme.colors.fill
    Slider(
        value = value,
        onValueChange = onValueChange,
        valueRange = valueRange,
        steps = steps,
        modifier = modifier,
        thumb = {
            Box(
                Modifier
                    .size(26.dp)
                    .shadow(3.dp, CircleShape)
                    .background(Color.White, CircleShape)
            )
        },
        track = { state ->
            val span = (state.valueRange.endInclusive - state.valueRange.start).takeIf { it > 0f } ?: 1f
            val fraction = ((state.value - state.valueRange.start) / span).coerceIn(0f, 1f)
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .background(trough, CircleShape)
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(fraction)
                        .height(4.dp)
                        .background(accent, CircleShape)
                )
            }
        },
    )
}

// ------------------------------------------------------------ status pill

/** Colour for a container/VM state — the single source for state colour. */
@Composable
fun statusTint(state: String): Color = when (state) {
    "running" -> AppTheme.colors.statusRunning
    "paused", "restarting", "booting", "stopping" -> AppTheme.colors.statusWarn
    "exited", "dead", "failed" -> AppTheme.colors.statusError
    else -> AppTheme.colors.statusNeutral
}

/**
 * State shown as a dot *and* a word. Colour alone can't carry the difference
 * between running and exited for someone who can't distinguish red from green.
 */
@Composable
fun StatusBadge(state: String, modifier: Modifier = Modifier) {
    val tint = statusTint(state)
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).background(tint, CircleShape))
        Spacer(Modifier.width(6.dp))
        Text(
            state.replaceFirstChar { it.uppercase() },
            style = MaterialTheme.typography.labelMedium,
            color = tint,
        )
    }
}

// ----------------------------------------------------------- empty states

@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = 56.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = AppTheme.colors.labelTertiary,
            modifier = Modifier.size(44.dp),
        )
        Spacer(Modifier.height(14.dp))
        Text(title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        Text(
            message,
            style = MaterialTheme.typography.bodySmall,
            color = AppTheme.colors.labelSecondary,
            textAlign = TextAlign.Center,
        )
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(20.dp))
            FilledAction(actionLabel, onAction)
        }
    }
}

// ------------------------------------------------------ segmented control

/**
 * The platform-standard way to switch between peer views of one thing — a
 * sliding knob over a filled trough, rather than an underlined tab strip.
 */
@Composable
fun SegmentedControl(
    items: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = rememberHaptics()
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .height(MinTouchTarget)
            .background(AppTheme.colors.fill, RoundedCornerShape(9.dp))
            .padding(2.dp),
    ) {
        val segment = maxWidth / items.size.coerceAtLeast(1)
        val offset by animateDpAsState(
            targetValue = segment * selectedIndex,
            animationSpec = spring(dampingRatio = 0.9f, stiffness = 700f),
            label = "segment",
        )
        Box(
            Modifier
                .offset(x = offset)
                .width(segment)
                .height(MinTouchTarget - 4.dp)
                .shadow(2.dp, RoundedCornerShape(7.dp))
                .background(AppTheme.colors.knob, RoundedCornerShape(7.dp))
        )
        Row(Modifier.fillMaxWidth()) {
            items.forEachIndexed { index, label ->
                val selected = index == selectedIndex
                Box(
                    Modifier
                        .weight(1f)
                        .height(MinTouchTarget - 4.dp)
                        .clickable {
                            if (!selected) haptics.selection()
                            onSelect(index)
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        label,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (selected) MaterialTheme.colorScheme.onSurface
                        else AppTheme.colors.labelSecondary,
                    )
                }
            }
        }
    }
}

// -------------------------------------------------------- large-title bar

/**
 * A screen with a collapsing large title: 34pt bold at rest, shrinking to the
 * 17pt inline title as content scrolls under it, with the separator appearing
 * only once content is actually behind the bar.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LargeTitleScaffold(
    title: String,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
    navigationIcon: @Composable () -> Unit = {},
    snackbarHost: @Composable () -> Unit = {},
    floatingActionButton: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    val scrollBehavior: TopAppBarScrollBehavior =
        TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val collapsed = scrollBehavior.state.collapsedFraction

    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = AppTheme.colors.base,
        snackbarHost = snackbarHost,
        floatingActionButton = floatingActionButton,
        topBar = {
            Column {
                LargeTopAppBar(
                    title = {
                        Text(
                            title,
                            // One lambda feeds both the expanded and collapsed
                            // slots, so the size is interpolated by hand to keep
                            // the crossfade from jumping.
                            fontSize = lerp(34f, 17f, collapsed).sp,
                            lineHeight = lerp(41f, 22f, collapsed).sp,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                            letterSpacing = lerp(-0.6f, -0.4f, collapsed).sp,
                            maxLines = 1,
                        )
                    },
                    navigationIcon = navigationIcon,
                    actions = actions,
                    scrollBehavior = scrollBehavior,
                    colors = TopAppBarDefaults.largeTopAppBarColors(
                        containerColor = AppTheme.colors.base,
                        scrolledContainerColor = AppTheme.colors.base,
                        titleContentColor = MaterialTheme.colorScheme.onBackground,
                        actionIconContentColor = MaterialTheme.colorScheme.primary,
                        navigationIconContentColor = MaterialTheme.colorScheme.primary,
                    ),
                )
                // Scroll-edge separator: absent at rest, drawn once content
                // has actually moved under the bar.
                Box(Modifier.alpha(collapsed)) { Hairline(startIndent = 0.dp) }
            }
        },
        content = content,
    )
}
