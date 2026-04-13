package com.openpod.core.ui.component

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.openpod.core.ui.R
import com.openpod.core.ui.theme.OpenPodTheme
import com.openpod.core.ui.theme.PodConnected

/**
 * Circular progress indicator for reservoir level and bolus delivery.
 *
 * Displays a ring that fills from 0% to the given [progress] with an
 * animated transition. A centered [label] and optional [sublabel] are
 * rendered inside the ring.
 *
 * The fill animation uses a 400ms EmphasizedDecelerate easing curve.
 *
 * @param progress Fill level from 0.0 (empty) to 1.0 (full).
 * @param label Primary text centered inside the ring, e.g., "148 U".
 * @param sublabel Secondary text below the label, e.g., "of 200 U".
 *   Null to hide.
 * @param color Color of the progress ring arc.
 * @param modifier Modifier applied to the root layout.
 */
@Composable
fun ProgressRing(
    progress: Float,
    label: String,
    sublabel: String? = null,
    color: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier,
) {
    val clampedProgress = progress.coerceIn(0f, 1f)
    val animatedProgress = remember { Animatable(0f) }
    val trackColor = MaterialTheme.colorScheme.outlineVariant
    val percentInt = (clampedProgress * 100).toInt()

    val accessibilityText = stringResource(
        R.string.progress_ring_accessibility,
        label,
        percentInt,
    )

    LaunchedEffect(clampedProgress) {
        animatedProgress.animateTo(
            targetValue = clampedProgress,
            animationSpec = tween(
                durationMillis = 400,
                easing = androidx.compose.animation.core.EaseOutExpo,
            ),
        )
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(120.dp)
            .semantics { contentDescription = accessibilityText },
    ) {
        Canvas(modifier = Modifier.matchParentSize()) {
            val strokeWidth = 8.dp.toPx()
            val stroke = Stroke(width = strokeWidth, cap = StrokeCap.Round)

            // Background track
            drawArc(
                color = trackColor,
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                style = stroke,
            )

            // Progress arc
            drawArc(
                color = color,
                startAngle = -90f,
                sweepAngle = 360f * animatedProgress.value,
                useCenter = false,
                style = stroke,
            )
        }

        // Centered labels
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
            if (sublabel != null) {
                Text(
                    text = sublabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0F0F12)
@Composable
private fun ProgressRingPreview() {
    OpenPodTheme(darkTheme = true) {
        ProgressRing(
            progress = 0.74f,
            label = "148 U",
            sublabel = "of 200 U",
            color = PodConnected,
            modifier = Modifier.padding(16.dp),
        )
    }
}
