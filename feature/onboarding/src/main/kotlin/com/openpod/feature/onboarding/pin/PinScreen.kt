package com.openpod.feature.onboarding.pin

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.keyframes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.openpod.core.ui.component.NumberPad
import com.openpod.core.ui.mvi.CollectEffects
import com.openpod.feature.onboarding.R
import kotlin.math.roundToInt

/**
 * Safety PIN setup screen with 4-digit entry, confirmation, and biometric opt-in.
 *
 * Uses the NumberPad component from core/ui for digit input, with PIN dots
 * visualization and shake animation on mismatch.
 *
 * @param onComplete Callback when PIN setup is finished (with or without biometrics).
 * @param onBack Callback when the user navigates back.
 * @param modifier Modifier applied to the root layout.
 * @param viewModel The [PinViewModel] managing screen state.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PinScreen(
    onComplete: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PinViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    CollectEffects(viewModel.effect) { effect ->
        when (effect) {
            PinEffect.NavigateToReady -> onComplete()
            PinEffect.NavigateBack -> onBack()
            PinEffect.ShowBiometricPrompt -> {
                // In a real implementation, this would trigger BiometricPrompt
                // For now, we skip to completion
                viewModel.onIntent(PinIntent.BiometricResult(success = false))
            }

            is PinEffect.ShowSnackbar -> {
                // Handled by snackbar host in a real implementation
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(modifier = Modifier.height(16.dp))

        // Title changes based on phase
        Text(
            text = when (state.phase) {
                PinPhase.ENTRY -> stringResource(R.string.onboarding_pin_title)
                PinPhase.CONFIRMATION -> stringResource(R.string.onboarding_pin_confirm_title)
                PinPhase.BIOMETRIC_PROMPT -> stringResource(R.string.onboarding_pin_title)
            },
            style = MaterialTheme.typography.headlineLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .fillMaxWidth()
                .semantics { heading() },
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = when (state.phase) {
                PinPhase.ENTRY -> stringResource(R.string.onboarding_pin_subtitle)
                PinPhase.CONFIRMATION -> stringResource(R.string.onboarding_pin_confirm_subtitle)
                PinPhase.BIOMETRIC_PROMPT -> ""
            },
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(modifier = Modifier.weight(0.3f))

        // PIN dots with shake animation
        PinDots(
            filledCount = state.digitCount,
            isError = state.showShakeAnimation,
            onShakeComplete = { viewModel.onIntent(PinIntent.ShakeComplete) },
        )

        Spacer(modifier = Modifier.height(12.dp))

        // Prompt text below dots
        Text(
            text = when (state.phase) {
                PinPhase.ENTRY -> stringResource(R.string.onboarding_pin_enter_prompt)
                PinPhase.CONFIRMATION -> stringResource(R.string.onboarding_pin_confirm_prompt)
                PinPhase.BIOMETRIC_PROMPT -> ""
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // Error message (if any)
        if (state.errorMessage != null) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = state.errorMessage!!,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive },
            )
        }

        // Show mismatch message after shake
        if (state.mismatchCount > 0 && !state.showShakeAnimation && state.phase == PinPhase.CONFIRMATION) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.onboarding_pin_mismatch),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive },
            )
        }

        Spacer(modifier = Modifier.weight(0.3f))

        // Number pad
        NumberPad(
            onDigit = { digit -> viewModel.onIntent(PinIntent.DigitEntered(digit)) },
            onDecimal = {}, // No decimal for PIN
            onBackspace = { viewModel.onIntent(PinIntent.Backspace) },
            onBackspaceLongPress = { viewModel.onIntent(PinIntent.ClearAll) },
            decimalEnabled = false,
            modifier = Modifier.padding(horizontal = 16.dp),
        )

        Spacer(modifier = Modifier.height(16.dp))
    }

    // Biometric bottom sheet
    if (state.showBiometricSheet) {
        ModalBottomSheet(
            onDismissRequest = { viewModel.onIntent(PinIntent.SkipBiometrics) },
            sheetState = rememberModalBottomSheetState(),
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            BiometricSheetContent(
                onEnable = { viewModel.onIntent(PinIntent.EnableBiometrics) },
                onSkip = { viewModel.onIntent(PinIntent.SkipBiometrics) },
            )
        }
    }
}

/**
 * PIN dot indicators that show filled/empty state and shake on error.
 *
 * @param filledCount Number of dots to show as filled (0-4).
 * @param isError Whether to show the error (shake + red) animation.
 * @param onShakeComplete Callback when the shake animation finishes.
 */
@Composable
private fun PinDots(
    filledCount: Int,
    isError: Boolean,
    onShakeComplete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shakeOffset = remember { Animatable(0f) }
    val dotsA11y = stringResource(R.string.onboarding_pin_dots_a11y, filledCount)

    LaunchedEffect(isError) {
        if (isError) {
            shakeOffset.animateTo(
                targetValue = 0f,
                animationSpec = keyframes {
                    durationMillis = 300
                    0f at 0
                    8f at 50
                    -8f at 100
                    8f at 150
                    -8f at 200
                    8f at 250
                    0f at 300
                },
            )
            onShakeComplete()
        }
    }

    val primaryColor = MaterialTheme.colorScheme.primary
    val outlineColor = MaterialTheme.colorScheme.outline
    val errorColor = MaterialTheme.colorScheme.error

    Row(
        modifier = modifier
            .offset { IntOffset(shakeOffset.value.roundToInt(), 0) }
            .semantics { contentDescription = dotsA11y },
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        repeat(PinViewModel.PIN_LENGTH) { index ->
            val isFilled = index < filledCount
            val dotColor = when {
                isError -> errorColor
                isFilled -> primaryColor
                else -> outlineColor
            }

            Canvas(modifier = Modifier.size(16.dp)) {
                if (isFilled || isError) {
                    drawCircle(color = dotColor)
                } else {
                    drawCircle(
                        color = dotColor,
                        style = Stroke(width = 2.dp.toPx()),
                    )
                }
            }
        }
    }
}

/**
 * Content for the biometric opt-in bottom sheet.
 */
@Composable
private fun BiometricSheetContent(
    onEnable: () -> Unit,
    onSkip: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = Icons.Filled.Fingerprint,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(48.dp),
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = stringResource(R.string.onboarding_pin_biometric_title),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = stringResource(R.string.onboarding_pin_biometric_description),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(modifier = Modifier.height(24.dp))

        Button(
            onClick = onEnable,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.surface,
            ),
        ) {
            Text(
                text = stringResource(R.string.onboarding_pin_biometric_enable),
                style = MaterialTheme.typography.titleMedium,
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        TextButton(onClick = onSkip) {
            Text(
                text = stringResource(R.string.onboarding_pin_biometric_skip),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}
