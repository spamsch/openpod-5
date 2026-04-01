package com.openpod.core.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.openpod.core.ui.R
import com.openpod.core.ui.theme.OpenPodTheme
import com.openpod.core.ui.theme.PillShape
import com.openpod.core.ui.theme.PodConnected
import com.openpod.core.ui.theme.PodError
import com.openpod.core.ui.theme.PodWarning
import com.openpod.model.pod.OperatingMode
import com.openpod.model.pod.PodConnectionState

/**
 * Persistent pod status capsule intended for the top of the screen.
 *
 * Displays a colored dot indicating connection state, pod label,
 * reservoir units remaining, and time until expiry inside a tonal
 * pill-shaped surface. The entire capsule is tappable to navigate
 * to the pod status screen.
 *
 * When no pod is paired (all optional parameters are null), the capsule
 * shows "No Pod" with a muted dot.
 */
@Composable
fun PodStatusBar(
    connectionState: PodConnectionState,
    reservoirUnits: Double?,
    timeRemainingHours: Double?,
    expiresAt: java.time.Instant? = null,
    operatingMode: OperatingMode?,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val hasPod = reservoirUnits != null

    val dotColor = when {
        !hasPod -> MaterialTheme.colorScheme.onSurfaceVariant
        connectionState == PodConnectionState.CONNECTED -> PodConnected
        connectionState == PodConnectionState.RECONNECTING ||
            connectionState == PodConnectionState.CONNECTING -> PodWarning
        else -> PodError
    }

    val accessibilityDescription = if (reservoirUnits != null) {
        val reservoirInt = reservoirUnits.toInt()
        val hoursInt = timeRemainingHours?.toInt() ?: 0
        when (connectionState) {
            PodConnectionState.CONNECTED ->
                stringResource(R.string.pod_status_connected, reservoirInt, hoursInt)
            PodConnectionState.RECONNECTING, PodConnectionState.CONNECTING ->
                stringResource(R.string.pod_status_reconnecting, reservoirInt, hoursInt)
            PodConnectionState.DISCONNECTED ->
                stringResource(R.string.pod_status_disconnected, reservoirInt, hoursInt)
        }
    } else {
        stringResource(R.string.pod_status_no_pod_description)
    }

    Surface(
        onClick = onTap,
        shape = PillShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .semantics { contentDescription = accessibilityDescription },
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        ) {
            // Status dot
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(dotColor),
            )

            // Pod label
            Text(
                text = if (hasPod) {
                    stringResource(R.string.pod_status_label)
                } else {
                    stringResource(R.string.pod_status_no_pod)
                },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )

            if (reservoirUnits != null) {
                Text(
                    text = "\u2022",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                // Reservoir
                Text(
                    text = stringResource(R.string.pod_status_reservoir_units, reservoirUnits.toInt()),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )

                if (timeRemainingHours != null) {
                    Text(
                        text = "\u2022",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    // Time remaining + expiry date
                    val expiryText = buildString {
                        append(stringResource(R.string.pod_status_time_remaining, timeRemainingHours.toInt()))
                        if (expiresAt != null) {
                            val formatter = java.time.format.DateTimeFormatter
                                .ofPattern("MMM d, h:mm a")
                                .withZone(java.time.ZoneId.systemDefault())
                            append(" (${formatter.format(expiresAt)})")
                        }
                    }
                    Text(
                        text = expiryText,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF111315)
@Composable
private fun PodStatusBarConnectedPreview() {
    OpenPodTheme(darkTheme = true) {
        PodStatusBar(
            connectionState = PodConnectionState.CONNECTED,
            reservoirUnits = 148.0,
            timeRemainingHours = 47.0,
            operatingMode = OperatingMode.AUTOMATIC,
            onTap = {},
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFFFBFAF7)
@Composable
private fun PodStatusBarLightPreview() {
    OpenPodTheme(darkTheme = false) {
        PodStatusBar(
            connectionState = PodConnectionState.CONNECTED,
            reservoirUnits = 148.0,
            timeRemainingHours = 47.0,
            operatingMode = OperatingMode.AUTOMATIC,
            onTap = {},
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF111315)
@Composable
private fun PodStatusBarNoPodPreview() {
    OpenPodTheme(darkTheme = true) {
        PodStatusBar(
            connectionState = PodConnectionState.DISCONNECTED,
            reservoirUnits = null,
            timeRemainingHours = null,
            operatingMode = null,
            onTap = {},
        )
    }
}
