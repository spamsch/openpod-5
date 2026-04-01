package com.openpod.feature.onboarding.ready

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.openpod.core.ui.mvi.CollectEffects
import com.openpod.feature.onboarding.R
import com.openpod.model.onboarding.ProfileSubStep

/**
 * Ready screen showing a summary of all configured onboarding values.
 *
 * Each setting is displayed as a card with an "Edit" link that navigates
 * back to the relevant setup screen. The "Pair Your First Pod" button
 * completes onboarding.
 *
 * @param onPairPod Callback when the user taps "Pair Your First Pod".
 * @param onEditProfile Callback to navigate to a profile sub-step for editing.
 * @param onEditPin Callback to navigate to the PIN screen for editing.
 * @param onBack Callback when the user navigates back.
 * @param modifier Modifier applied to the root layout.
 * @param viewModel The [ReadyViewModel] managing screen state.
 */
@Composable
fun ReadyScreen(
    onPairPod: () -> Unit,
    onEditProfile: (ProfileSubStep) -> Unit,
    onEditPin: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ReadyViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    val pairPodA11y = stringResource(R.string.onboarding_ready_pair_pod_a11y)

    CollectEffects(viewModel.effect) { effect ->
        when (effect) {
            ReadyEffect.CompleteOnboarding -> onPairPod()
            is ReadyEffect.NavigateToProfile -> onEditProfile(effect.subStep)
            ReadyEffect.NavigateToPin -> onEditPin()
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 16.dp),
    ) {
        Spacer(modifier = Modifier.height(16.dp))

        // Header
        Text(
            text = stringResource(R.string.onboarding_ready_title),
            style = MaterialTheme.typography.headlineLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.semantics { heading() },
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = stringResource(R.string.onboarding_ready_subtitle),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(modifier = Modifier.height(24.dp))

        if (state.isLoading) {
            CircularProgressIndicator(
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
        } else {
            // Summary cards (scrollable)
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // DIA card
                SummaryCard(
                    label = stringResource(R.string.onboarding_ready_dia_label),
                    value = state.diaSummary?.value
                        ?: stringResource(R.string.onboarding_ready_not_configured),
                    onEdit = { viewModel.onIntent(ReadyIntent.EditProfile(ProfileSubStep.DIA)) },
                    editA11y = stringResource(
                        R.string.onboarding_ready_edit_a11y,
                        stringResource(R.string.onboarding_ready_dia_label),
                    ),
                )

                // IC card
                SummaryCard(
                    label = stringResource(R.string.onboarding_ready_ic_label),
                    value = state.icSummary?.value
                        ?: stringResource(R.string.onboarding_ready_not_configured),
                    details = state.icSummary?.details ?: emptyList(),
                    onEdit = { viewModel.onIntent(ReadyIntent.EditProfile(ProfileSubStep.IC_RATIO)) },
                    editA11y = stringResource(
                        R.string.onboarding_ready_edit_a11y,
                        stringResource(R.string.onboarding_ready_ic_label),
                    ),
                )

                // CF card
                SummaryCard(
                    label = stringResource(R.string.onboarding_ready_cf_label),
                    value = state.cfSummary?.value
                        ?: stringResource(R.string.onboarding_ready_not_configured),
                    details = state.cfSummary?.details ?: emptyList(),
                    onEdit = { viewModel.onIntent(ReadyIntent.EditProfile(ProfileSubStep.CORRECTION_FACTOR)) },
                    editA11y = stringResource(
                        R.string.onboarding_ready_edit_a11y,
                        stringResource(R.string.onboarding_ready_cf_label),
                    ),
                )

                // Target card
                SummaryCard(
                    label = stringResource(R.string.onboarding_ready_target_label),
                    value = state.targetSummary?.value
                        ?: stringResource(R.string.onboarding_ready_not_configured),
                    details = state.targetSummary?.details ?: emptyList(),
                    onEdit = { viewModel.onIntent(ReadyIntent.EditProfile(ProfileSubStep.TARGET_GLUCOSE)) },
                    editA11y = stringResource(
                        R.string.onboarding_ready_edit_a11y,
                        stringResource(R.string.onboarding_ready_target_label),
                    ),
                )

                // Basal card
                SummaryCard(
                    label = stringResource(R.string.onboarding_ready_basal_label),
                    value = state.basalSummary?.value
                        ?: stringResource(R.string.onboarding_ready_not_configured),
                    details = state.basalSummary?.details ?: emptyList(),
                    onEdit = { viewModel.onIntent(ReadyIntent.EditProfile(ProfileSubStep.BASAL_PROGRAM)) },
                    editA11y = stringResource(
                        R.string.onboarding_ready_edit_a11y,
                        stringResource(R.string.onboarding_ready_basal_label),
                    ),
                )

                // PIN card
                PinSummaryCard(
                    pinEnabled = state.pinEnabled,
                    biometricEnabled = state.biometricEnabled,
                    onEdit = { viewModel.onIntent(ReadyIntent.EditPin) },
                    editA11y = stringResource(
                        R.string.onboarding_ready_edit_a11y,
                        stringResource(R.string.onboarding_ready_pin_label),
                    ),
                )

                Spacer(modifier = Modifier.height(8.dp))
            }
        }

        // Pair Your First Pod button
        Button(
            onClick = { viewModel.onIntent(ReadyIntent.PairPod) },
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .semantics { contentDescription = pairPodA11y },
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.surface,
            ),
        ) {
            Text(
                text = stringResource(R.string.onboarding_ready_pair_pod),
                style = MaterialTheme.typography.titleMedium,
            )
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}

/**
 * Summary card for a single profile setting.
 *
 * @param label Setting name displayed at the top.
 * @param value Primary display value.
 * @param details Additional detail lines (segment summaries).
 * @param onEdit Callback when "Edit" is tapped.
 * @param editA11y Accessibility description for the edit button.
 */
@Composable
private fun SummaryCard(
    label: String,
    value: String,
    details: List<String> = emptyList(),
    onEdit: () -> Unit,
    editA11y: String,
    modifier: Modifier = Modifier,
) {
    val editText = stringResource(R.string.onboarding_ready_edit)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant,
                shape = RoundedCornerShape(12.dp),
            )
            .background(
                color = MaterialTheme.colorScheme.surfaceContainer,
                shape = RoundedCornerShape(12.dp),
            )
            .padding(16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = value,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }

            TextButton(
                onClick = onEdit,
                modifier = Modifier.semantics { contentDescription = editA11y },
            ) {
                Text(
                    text = editText,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }

        // Detail lines (for multi-segment settings)
        details.take(3).forEach { detail ->
            Text(
                text = detail,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (details.size > 3) {
            Text(
                text = stringResource(R.string.onboarding_ready_and_more, details.size - 3),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Specialized summary card for PIN and biometric status.
 */
@Composable
private fun PinSummaryCard(
    pinEnabled: Boolean,
    biometricEnabled: Boolean,
    onEdit: () -> Unit,
    editA11y: String,
    modifier: Modifier = Modifier,
) {
    val editText = stringResource(R.string.onboarding_ready_edit)
    val pinLabel = stringResource(R.string.onboarding_ready_pin_label)
    val enabledText = stringResource(R.string.onboarding_ready_pin_enabled)
    val biometricText = if (biometricEnabled) {
        stringResource(R.string.onboarding_ready_biometrics_on)
    } else {
        stringResource(R.string.onboarding_ready_biometrics_off)
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant,
                shape = RoundedCornerShape(12.dp),
            )
            .background(
                color = MaterialTheme.colorScheme.surfaceContainer,
                shape = RoundedCornerShape(12.dp),
            )
            .padding(16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = pinLabel,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            TextButton(
                onClick = onEdit,
                modifier = Modifier.semantics { contentDescription = editA11y },
            ) {
                Text(
                    text = editText,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }

        Text(
            text = "$enabledText  \u2022  $biometricText",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
