package com.openpod.core.ui.component

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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

/**
 * Bar-based step indicator for multi-step wizard flows such as onboarding
 * profile setup and pod pairing.
 *
 * Displays a horizontal row of bar segments that fill the available width.
 * Completed segments are filled with the primary color, the active segment
 * pulses gently, and pending segments use a muted outline variant color.
 *
 * @param totalSteps Total number of steps in the wizard.
 * @param currentStep The current active step (1-based index).
 * @param onStepTap Optional callback when a completed step is tapped to
 *   navigate back. Pass null to make the stepper non-interactive.
 * @param modifier Modifier applied to the root layout.
 */
@Composable
fun WizardStepper(
    totalSteps: Int,
    currentStep: Int,
    onStepTap: ((Int) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    require(totalSteps >= 1) { "totalSteps must be at least 1, got $totalSteps" }
    require(currentStep in 1..totalSteps) {
        "currentStep ($currentStep) must be between 1 and $totalSteps"
    }

    val primaryColor = MaterialTheme.colorScheme.primary
    val outlineVariantColor = MaterialTheme.colorScheme.outlineVariant

    Row(
        modifier = modifier
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        for (step in 1..totalSteps) {
            val state = when {
                step < currentStep -> StepState.COMPLETED
                step == currentStep -> StepState.ACTIVE
                else -> StepState.PENDING
            }

            val stepDescription = when (state) {
                StepState.COMPLETED -> stringResource(R.string.wizard_step_completed, step, totalSteps)
                StepState.ACTIVE -> stringResource(R.string.wizard_step_active, step, totalSteps)
                StepState.PENDING -> stringResource(R.string.wizard_step_pending, step, totalSteps)
            }

            when (state) {
                StepState.COMPLETED -> {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(4.dp)
                            .clip(PillShape)
                            .background(primaryColor)
                            .semantics { contentDescription = stepDescription }
                            .then(
                                if (onStepTap != null) {
                                    Modifier.clickable { onStepTap(step) }
                                } else {
                                    Modifier
                                }
                            ),
                    )
                }

                StepState.ACTIVE -> {
                    val infiniteTransition = rememberInfiniteTransition(label = "stepPulse")
                    val pulseAlpha by infiniteTransition.animateFloat(
                        initialValue = 1f,
                        targetValue = 0.5f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(durationMillis = 1500, easing = LinearEasing),
                            repeatMode = RepeatMode.Reverse,
                        ),
                        label = "pulseAlpha",
                    )

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(4.dp)
                            .clip(PillShape)
                            .background(primaryColor.copy(alpha = pulseAlpha))
                            .semantics { contentDescription = stepDescription },
                    )
                }

                StepState.PENDING -> {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(4.dp)
                            .clip(PillShape)
                            .background(outlineVariantColor)
                            .semantics { contentDescription = stepDescription },
                    )
                }
            }
        }
    }
}

/** Internal state classification for a single wizard step. */
private enum class StepState {
    COMPLETED,
    ACTIVE,
    PENDING,
}

@Preview(showBackground = true, backgroundColor = 0xFF111315)
@Composable
private fun WizardStepperPreview() {
    OpenPodTheme(darkTheme = true) {
        WizardStepper(
            totalSteps = 5,
            currentStep = 3,
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFFFBFAF7)
@Composable
private fun WizardStepperLightPreview() {
    OpenPodTheme(darkTheme = false) {
        WizardStepper(
            totalSteps = 5,
            currentStep = 3,
        )
    }
}
