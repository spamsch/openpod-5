package com.openpod.core.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.openpod.core.ui.R
import com.openpod.core.ui.theme.InsulinActive
import com.openpod.core.ui.theme.OpenPodTheme
import com.openpod.core.ui.theme.PillShape
import java.util.Locale

/**
 * Compact Insulin-on-Board (IOB) pill.
 *
 * Shows the current IOB value with a water drop icon and optional
 * decay indicator arrow in a pill-shaped tonal surface. The entire
 * pill is tappable, intended to navigate to an IOB detail screen.
 */
@Composable
fun IobChip(
    iobUnits: Double?,
    isDecaying: Boolean = false,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val displayText = if (iobUnits != null) {
        stringResource(
            R.string.iob_value_active,
            String.format(Locale.US, "%.2f", iobUnits),
        )
    } else {
        stringResource(R.string.iob_no_value)
    }

    val decayText = if (isDecaying) stringResource(R.string.iob_decay_indicator) else null
    val accessibilityText = buildString {
        append(displayText)
        if (decayText != null) {
            append(", ")
            append(decayText)
        }
    }

    Surface(
        onClick = onClick,
        shape = PillShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier
            .semantics { contentDescription = accessibilityText },
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        ) {
            Icon(
                imageVector = Icons.Default.WaterDrop,
                contentDescription = stringResource(R.string.iob_icon_description),
                tint = InsulinActive,
                modifier = Modifier.size(16.dp),
            )

            Text(
                text = displayText,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )

            if (isDecaying) {
                Icon(
                    imageVector = Icons.Default.ArrowDownward,
                    contentDescription = stringResource(R.string.iob_decay_icon_description),
                    tint = InsulinActive,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF111315)
@Composable
private fun IobChipPreview() {
    OpenPodTheme(darkTheme = true) {
        IobChip(
            iobUnits = 2.45,
            isDecaying = true,
            onClick = {},
            modifier = Modifier.padding(16.dp),
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFFFBFAF7)
@Composable
private fun IobChipLightPreview() {
    OpenPodTheme(darkTheme = false) {
        IobChip(
            iobUnits = 2.45,
            isDecaying = true,
            onClick = {},
            modifier = Modifier.padding(16.dp),
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF111315)
@Composable
private fun IobChipNoValuePreview() {
    OpenPodTheme(darkTheme = true) {
        IobChip(
            iobUnits = null,
            isDecaying = false,
            onClick = {},
            modifier = Modifier.padding(16.dp),
        )
    }
}
