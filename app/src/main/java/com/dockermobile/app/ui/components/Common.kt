package com.dockermobile.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.dockermobile.app.docker.UiPort
import com.dockermobile.app.ui.theme.AppTheme

/**
 * State dot. Only ever used next to a text label — see [StatusBadge] — so the
 * state never depends on colour perception alone.
 */
@Composable
fun StatusDot(state: String, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(8.dp)
            .background(statusTint(state), CircleShape)
    )
}

/** Grouped card with an optional header, matching the inset-grouped list style. */
@Composable
fun SectionCard(
    modifier: Modifier = Modifier,
    title: String? = null,
    footer: String? = null,
    content: @Composable () -> Unit,
) {
    InsetGroup(modifier = modifier, header = title, footer = footer) {
        GroupBody { content() }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PortChips(
    ports: List<UiPort>,
    enabled: Boolean = true,
    onTap: (UiPort) -> Unit,
) {
    if (ports.none { it.publicPort != null }) return
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        ports.filter { it.publicPort != null }.forEach { p ->
            TapTag(
                label = "${p.publicPort} → ${p.containerPort}",
                icon = Icons.AutoMirrored.Filled.OpenInNew,
                onClick = { if (enabled) onTap(p) },
            )
        }
    }
}

/**
 * Inline, non-modal error. Failures that people can keep working around belong
 * next to the content, not behind an alert.
 */
@Composable
fun ErrorBanner(message: String?, onDismiss: () -> Unit = {}) {
    if (message.isNullOrBlank()) return
    Surface(
        color = AppTheme.colors.statusError.copy(alpha = if (AppTheme.colors.isDark) 0.18f else 0.10f),
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(start = 16.dp, top = 6.dp, bottom = 6.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                message,
                style = MaterialTheme.typography.bodySmall,
                color = AppTheme.colors.statusError,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onDismiss) {
                Text("Dismiss", style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

/**
 * Confirmation for an irreversible action. Destructive verbs are coloured and
 * named ("Remove", not "OK") so the outcome is readable from the button alone.
 */
@Composable
fun ConfirmDialog(
    title: String,
    body: String,
    confirmLabel: String = "Confirm",
    destructive: Boolean = true,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val haptics = rememberHaptics()
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = MaterialTheme.shapes.large,
        containerColor = AppTheme.colors.elevated,
        title = { Text(title, style = MaterialTheme.typography.titleLarge) },
        text = {
            Text(
                body,
                style = MaterialTheme.typography.bodySmall,
                color = AppTheme.colors.labelSecondary,
            )
        },
        confirmButton = {
            TextButton(onClick = { haptics.warning(); onConfirm(); onDismiss() }) {
                Text(
                    confirmLabel,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (destructive) AppTheme.colors.statusError
                    else MaterialTheme.colorScheme.primary,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", style = MaterialTheme.typography.labelLarge)
            }
        },
    )
}

/** Monospace, log-style text block. */
@Composable
fun MonoText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurface,
) {
    Text(
        text,
        modifier = modifier,
        fontFamily = FontFamily.Monospace,
        style = MaterialTheme.typography.labelMedium,
        color = color,
    )
}

/** Label on the left, value on the right — the standard settings row shape. */
@Composable
fun KeyValueRow(key: String, value: String) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(key, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.width(12.dp))
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = AppTheme.colors.labelSecondary,
            modifier = Modifier.weight(1f),
            textAlign = androidx.compose.ui.text.style.TextAlign.End,
        )
    }
}
