package com.openpod.core.ui.component

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.openpod.core.ui.R
import com.openpod.core.ui.theme.OpenPodTheme

/**
 * Custom number pad for carb, blood glucose, and bolus entry.
 *
 * Renders a 3x4 grid with digits 1-9, a decimal point, 0, and a
 * backspace key. All keys provide haptic feedback on press. The decimal
 * key can be disabled for integer-only inputs (e.g., carbs).
 *
 * Long-pressing the backspace key triggers [onBackspaceLongPress],
 * intended for clearing the entire field.
 *
 * @param onDigit Called with the digit character ('0'-'9') when pressed.
 * @param onDecimal Called when the decimal point key is pressed.
 * @param onBackspace Called when the backspace key is pressed.
 * @param onBackspaceLongPress Called when the backspace key is long-pressed.
 * @param decimalEnabled Whether the decimal point key is interactive.
 *   When false, the key is dimmed and non-interactive.
 * @param modifier Modifier applied to the root layout.
 */
@Composable
fun NumberPad(
    onDigit: (Char) -> Unit,
    onDecimal: () -> Unit,
    onBackspace: () -> Unit,
    onBackspaceLongPress: () -> Unit,
    decimalEnabled: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val haptic = LocalHapticFeedback.current

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // Row 1: 1, 2, 3
        NumberPadRow {
            for (digit in '1'..'3') {
                DigitKey(
                    digit = digit,
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onDigit(digit)
                    },
                )
            }
        }
        // Row 2: 4, 5, 6
        NumberPadRow {
            for (digit in '4'..'6') {
                DigitKey(
                    digit = digit,
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onDigit(digit)
                    },
                )
            }
        }
        // Row 3: 7, 8, 9
        NumberPadRow {
            for (digit in '7'..'9') {
                DigitKey(
                    digit = digit,
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onDigit(digit)
                    },
                )
            }
        }
        // Row 4: decimal, 0, backspace
        NumberPadRow {
            DecimalKey(
                enabled = decimalEnabled,
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onDecimal()
                },
            )
            DigitKey(
                digit = '0',
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onDigit('0')
                },
            )
            BackspaceKey(
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onBackspace()
                },
                onLongClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onBackspaceLongPress()
                },
            )
        }
    }
}

@Composable
private fun NumberPadRow(content: @Composable () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        content()
    }
}

/**
 * A single digit key (0-9).
 */
@Composable
private fun DigitKey(
    digit: Char,
    onClick: () -> Unit,
) {
    val description = stringResource(R.string.number_pad_digit, digit.toString())
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier
            .widthIn(min = 64.dp)
            .heightIn(min = 56.dp)
            .semantics { contentDescription = description },
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = digit.toString(),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

/**
 * The decimal point key. Dims and becomes non-interactive when disabled.
 */
@Composable
private fun DecimalKey(
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val description = stringResource(R.string.number_pad_decimal)
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier
            .widthIn(min = 64.dp)
            .heightIn(min = 56.dp)
            .semantics { contentDescription = description },
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = ".",
                style = MaterialTheme.typography.headlineMedium,
                color = if (enabled) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                },
            )
        }
    }
}

/**
 * Backspace key with long-press support for clearing the field.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BackspaceKey(
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val description = stringResource(R.string.number_pad_backspace)
    val longPressDescription = stringResource(R.string.number_pad_backspace_long_press)
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier
            .widthIn(min = 64.dp)
            .heightIn(min = 56.dp)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
                onLongClickLabel = longPressDescription,
            )
            .semantics { contentDescription = description },
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.Backspace,
                contentDescription = null, // parent has contentDescription
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(24.dp),
            )
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0F0F12)
@Composable
private fun NumberPadPreview() {
    OpenPodTheme(darkTheme = true) {
        NumberPad(
            onDigit = {},
            onDecimal = {},
            onBackspace = {},
            onBackspaceLongPress = {},
            decimalEnabled = true,
            modifier = Modifier.padding(16.dp),
        )
    }
}
