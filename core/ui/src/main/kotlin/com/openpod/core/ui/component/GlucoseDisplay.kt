package com.openpod.core.ui.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.TrendingDown
import androidx.compose.material.icons.filled.TrendingFlat
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.openpod.core.ui.R
import com.openpod.core.ui.theme.GlucoseHigh
import com.openpod.core.ui.theme.GlucoseInRange
import com.openpod.core.ui.theme.GlucoseLow
import com.openpod.core.ui.theme.GlucoseUrgentLow
import com.openpod.core.ui.theme.HeroGlucoseStyle
import com.openpod.core.ui.theme.OpenPodTheme
import com.openpod.model.glucose.GlucoseRange
import com.openpod.model.glucose.GlucoseTrend

/**
 * Hero glucose value display showing the current reading with trend
 * direction, unit label, and freshness indicator.
 *
 * The value is color-coded by glucose range: green for in-range, amber
 * for high, red for low, and bright red for urgent low. When no reading
 * is available, "--" is displayed in a muted color.
 *
 * Uses 72sp hero metric style with tabular numerals to prevent layout
 * shifts when the value changes.
 *
 * This composable is a live region for accessibility: screen readers
 * announce updates automatically.
 */
@Composable
fun GlucoseDisplay(
    glucoseValue: Int?,
    unit: String,
    trend: GlucoseTrend?,
    freshnessText: String?,
    glucoseRange: GlucoseRange?,
    modifier: Modifier = Modifier,
) {
    val trendLabel = trend?.let { trendToString(it) }
    val trendIcon = trend?.let { trendToIcon(it) }

    val valueColor = when (glucoseRange) {
        GlucoseRange.IN_RANGE -> GlucoseInRange
        GlucoseRange.HIGH, GlucoseRange.VERY_HIGH -> GlucoseHigh
        GlucoseRange.LOW -> GlucoseLow
        GlucoseRange.URGENT_LOW -> GlucoseUrgentLow
        null -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    val noValue = stringResource(R.string.glucose_no_value)
    val displayValue = glucoseValue?.toString() ?: noValue

    val accessibilityText = if (glucoseValue != null) {
        stringResource(
            R.string.glucose_accessibility,
            displayValue,
            unit,
            trendLabel ?: "",
            freshnessText ?: "",
        )
    } else {
        stringResource(R.string.glucose_accessibility_no_value)
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier.semantics {
            liveRegion = LiveRegionMode.Polite
            contentDescription = accessibilityText
        },
    ) {
        // Trend label with directional icon
        if (trendLabel != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (trendIcon != null) {
                    Icon(
                        imageVector = trendIcon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                }
                Text(
                    text = trendLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
        }

        // Glucose value — 72sp hero metric
        Text(
            text = displayValue,
            style = HeroGlucoseStyle,
            color = valueColor,
            textAlign = TextAlign.Center,
        )

        // Unit and freshness on one line
        val subText = buildString {
            append(unit)
            if (freshnessText != null) {
                append("  \u00B7  ")
                append(freshnessText)
            }
        }
        Text(
            text = subText,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * Converts a [GlucoseTrend] to a localized display string.
 */
@Composable
private fun trendToString(trend: GlucoseTrend): String = when (trend) {
    GlucoseTrend.RISING_QUICKLY -> stringResource(R.string.glucose_trend_rising_quickly)
    GlucoseTrend.RISING -> stringResource(R.string.glucose_trend_rising)
    GlucoseTrend.STEADY -> stringResource(R.string.glucose_trend_steady)
    GlucoseTrend.FALLING -> stringResource(R.string.glucose_trend_falling)
    GlucoseTrend.FALLING_QUICKLY -> stringResource(R.string.glucose_trend_falling_quickly)
    GlucoseTrend.UNKNOWN -> stringResource(R.string.glucose_trend_unknown)
}

/**
 * Maps a [GlucoseTrend] to a Material icon for visual direction.
 */
private fun trendToIcon(trend: GlucoseTrend): ImageVector? = when (trend) {
    GlucoseTrend.RISING_QUICKLY -> Icons.Default.ArrowUpward
    GlucoseTrend.RISING -> Icons.Default.TrendingUp
    GlucoseTrend.STEADY -> Icons.Default.TrendingFlat
    GlucoseTrend.FALLING -> Icons.Default.TrendingDown
    GlucoseTrend.FALLING_QUICKLY -> Icons.Default.ArrowDownward
    GlucoseTrend.UNKNOWN -> null
}

@Preview(showBackground = true, backgroundColor = 0xFF111315)
@Composable
private fun GlucoseDisplayInRangePreview() {
    OpenPodTheme(darkTheme = true) {
        GlucoseDisplay(
            glucoseValue = 142,
            unit = "mg/dL",
            trend = GlucoseTrend.RISING,
            freshnessText = "2 min ago",
            glucoseRange = GlucoseRange.IN_RANGE,
            modifier = Modifier.padding(16.dp),
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFFFBFAF7)
@Composable
private fun GlucoseDisplayLightPreview() {
    OpenPodTheme(darkTheme = false) {
        GlucoseDisplay(
            glucoseValue = 142,
            unit = "mg/dL",
            trend = GlucoseTrend.RISING,
            freshnessText = "2 min ago",
            glucoseRange = GlucoseRange.IN_RANGE,
            modifier = Modifier.padding(16.dp),
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF111315)
@Composable
private fun GlucoseDisplayNoValuePreview() {
    OpenPodTheme(darkTheme = true) {
        GlucoseDisplay(
            glucoseValue = null,
            unit = "mg/dL",
            trend = null,
            freshnessText = null,
            glucoseRange = null,
            modifier = Modifier.padding(16.dp),
        )
    }
}
