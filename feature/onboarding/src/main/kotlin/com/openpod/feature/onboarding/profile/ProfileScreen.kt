package com.openpod.feature.onboarding.profile

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.openpod.core.ui.component.WizardStepper
import com.openpod.core.ui.mvi.CollectEffects
import com.openpod.feature.onboarding.R
import com.openpod.model.onboarding.ProfileSubStep
import com.openpod.model.profile.InsulinProfile
import com.openpod.model.profile.InsulinType
import java.time.format.DateTimeFormatter

/** Time formatter for displaying segment start/end times. */
private val TIME_FORMAT = DateTimeFormatter.ofPattern("h:mm a")

/**
 * Profile setup screen with a 5-sub-step wizard.
 *
 * Guides the user through configuring their insulin therapy parameters:
 * DIA, IC ratio, correction factor, target glucose, and basal program.
 *
 * @param onComplete Callback when all sub-steps are completed.
 * @param onBack Callback when the user navigates back from sub-step 1.
 * @param modifier Modifier applied to the root layout.
 * @param viewModel The [ProfileViewModel] managing screen state.
 */
@Composable
fun ProfileScreen(
    onComplete: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ProfileViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    CollectEffects(viewModel.effect) { effect ->
        when (effect) {
            ProfileEffect.NavigateToPin -> onComplete()
            ProfileEffect.NavigateBack -> onBack()
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
            text = stringResource(R.string.onboarding_profile_title),
            style = MaterialTheme.typography.headlineLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.semantics { heading() },
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = stringResource(R.string.onboarding_profile_subtitle),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Wizard stepper
        WizardStepper(
            totalSteps = 5,
            currentStep = state.currentSubStep.stepNumber,
            onStepTap = { step -> viewModel.onIntent(ProfileIntent.GoToStep(step)) },
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Animated sub-step content
        AnimatedContent(
            targetState = state.currentSubStep,
            modifier = Modifier.weight(1f),
            transitionSpec = {
                val direction = if (targetState.stepNumber > initialState.stepNumber) 1 else -1
                slideInHorizontally { fullWidth -> direction * fullWidth } togetherWith
                    slideOutHorizontally { fullWidth -> -direction * fullWidth }
            },
            label = "profileSubStep",
        ) { subStep ->
            when (subStep) {
                ProfileSubStep.DIA -> DiaSubStep(state, viewModel)
                ProfileSubStep.IC_RATIO -> IcRatioSubStep(state, viewModel)
                ProfileSubStep.CORRECTION_FACTOR -> CfSubStep(state, viewModel)
                ProfileSubStep.TARGET_GLUCOSE -> TargetSubStep(state, viewModel)
                ProfileSubStep.BASAL_PROGRAM -> BasalSubStep(state, viewModel)
            }
        }

        // Navigation buttons
        val backA11y = stringResource(R.string.onboarding_profile_back_a11y)
        val nextA11y = stringResource(R.string.onboarding_profile_next_a11y)

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Back button
            Button(
                onClick = { viewModel.onIntent(ProfileIntent.Back) },
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
                    .semantics { contentDescription = backA11y },
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                ),
            ) {
                Text(
                    text = stringResource(R.string.onboarding_profile_back),
                    style = MaterialTheme.typography.titleSmall,
                )
            }

            // Next button
            Button(
                onClick = { viewModel.onIntent(ProfileIntent.Next) },
                modifier = Modifier
                    .weight(2f)
                    .height(48.dp)
                    .semantics { contentDescription = nextA11y },
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.surface,
                ),
            ) {
                Text(
                    text = stringResource(R.string.onboarding_profile_next),
                    style = MaterialTheme.typography.titleSmall,
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}

// ────────────────────────────────────────────────────────────────
// Sub-Step 1: DIA
// ────────────────────────────────────────────────────────────────

/**
 * DIA sub-step: slider with insulin type presets.
 */
@Composable
private fun DiaSubStep(
    state: ProfileState,
    viewModel: ProfileViewModel,
) {
    val sliderA11y = stringResource(R.string.onboarding_profile_dia_slider_a11y, String.format("%.1f", state.diaValue))
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(scrollState),
    ) {
        Text(
            text = stringResource(R.string.onboarding_profile_dia_title),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = stringResource(R.string.onboarding_profile_dia_description),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(modifier = Modifier.height(24.dp))

        // DIA value display
        Text(
            text = String.format("%.1f", state.diaValue),
            style = MaterialTheme.typography.displaySmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )

        // Slider
        Slider(
            value = state.diaValue.toFloat(),
            onValueChange = { viewModel.onIntent(ProfileIntent.UpdateDia(it.toDouble())) },
            valueRange = 2.0f..5.0f,
            steps = 5, // 2.0, 2.5, 3.0, 3.5, 4.0, 4.5, 5.0 → 5 intermediate stops
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp)
                .semantics { contentDescription = sliderA11y },
        )

        // Range labels
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = stringResource(R.string.onboarding_profile_dia_min),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(R.string.onboarding_profile_dia_max),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = stringResource(R.string.onboarding_profile_dia_unit),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Insulin type presets
        Text(
            text = stringResource(R.string.onboarding_profile_dia_presets_title),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(modifier = Modifier.height(8.dp))

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.outlineVariant,
                    shape = RoundedCornerShape(12.dp),
                )
                .background(
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    shape = RoundedCornerShape(12.dp),
                ),
        ) {
            // Only show rapid-acting types (exclude OTHER as it doesn't have a preset DIA)
            val presets = remember {
                InsulinType.entries.filter { it != InsulinType.OTHER }
            }
            presets.forEachIndexed { index, type ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            viewModel.onIntent(ProfileIntent.SelectInsulinType(type))
                        }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = type.displayName,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (state.selectedInsulinType == type) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                    )
                    Text(
                        text = String.format("%.1f hr", type.defaultDiaHours),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = stringResource(R.string.onboarding_profile_dia_presets_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(modifier = Modifier.height(16.dp))
    }
}

// ────────────────────────────────────────────────────────────────
// Sub-Step 2: IC Ratio
// ────────────────────────────────────────────────────────────────

/**
 * IC ratio sub-step: single-value segments for grams-per-unit.
 */
@Composable
private fun IcRatioSubStep(
    state: ProfileState,
    viewModel: ProfileViewModel,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
    ) {
        Text(
            text = stringResource(R.string.onboarding_profile_ic_title),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = stringResource(R.string.onboarding_profile_ic_description),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = stringResource(R.string.onboarding_profile_time_segments),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(modifier = Modifier.height(8.dp))

        state.icSegments.forEachIndexed { index, segment ->
            SingleValueSegmentCard(
                segment = segment,
                label = stringResource(R.string.onboarding_profile_ic_label),
                suffix = stringResource(R.string.onboarding_profile_ic_suffix),
                onValueChange = { viewModel.onIntent(ProfileIntent.UpdateIcSegment(index, it)) },
            )
            Spacer(modifier = Modifier.height(8.dp))
        }

        if (state.icSegments.size < InsulinProfile.MAX_IC_CF_SEGMENTS) {
            TextButton(
                onClick = { viewModel.onIntent(ProfileIntent.AddIcSegment) },
                modifier = Modifier.semantics {
                    contentDescription = "Add another time segment"
                },
            ) {
                Text(
                    text = stringResource(R.string.onboarding_profile_add_segment),
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = stringResource(R.string.onboarding_profile_ic_range_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(modifier = Modifier.height(16.dp))
    }
}

// ────────────────────────────────────────────────────────────────
// Sub-Step 3: Correction Factor
// ────────────────────────────────────────────────────────────────

/**
 * Correction factor sub-step: single-value segments for mg/dL per unit.
 */
@Composable
private fun CfSubStep(
    state: ProfileState,
    viewModel: ProfileViewModel,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
    ) {
        Text(
            text = stringResource(R.string.onboarding_profile_cf_title),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = stringResource(R.string.onboarding_profile_cf_description),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = stringResource(R.string.onboarding_profile_time_segments),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(modifier = Modifier.height(8.dp))

        state.cfSegments.forEachIndexed { index, segment ->
            SingleValueSegmentCard(
                segment = segment,
                label = stringResource(R.string.onboarding_profile_cf_label),
                suffix = stringResource(R.string.onboarding_profile_cf_suffix),
                onValueChange = { viewModel.onIntent(ProfileIntent.UpdateCfSegment(index, it)) },
            )
            Spacer(modifier = Modifier.height(8.dp))
        }

        if (state.cfSegments.size < InsulinProfile.MAX_IC_CF_SEGMENTS) {
            TextButton(
                onClick = { viewModel.onIntent(ProfileIntent.AddCfSegment) },
                modifier = Modifier.semantics {
                    contentDescription = "Add another time segment"
                },
            ) {
                Text(
                    text = stringResource(R.string.onboarding_profile_add_segment),
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = stringResource(R.string.onboarding_profile_cf_range_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(modifier = Modifier.height(16.dp))
    }
}

// ────────────────────────────────────────────────────────────────
// Sub-Step 4: Target Glucose
// ────────────────────────────────────────────────────────────────

/**
 * Target glucose sub-step: dual-value segments (low/high).
 */
@Composable
private fun TargetSubStep(
    state: ProfileState,
    viewModel: ProfileViewModel,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
    ) {
        Text(
            text = stringResource(R.string.onboarding_profile_target_title),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = stringResource(R.string.onboarding_profile_target_description),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = stringResource(R.string.onboarding_profile_time_segments),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(modifier = Modifier.height(8.dp))

        state.targetSegments.forEachIndexed { index, segment ->
            TargetSegmentCard(
                segment = segment,
                onLowChange = { viewModel.onIntent(ProfileIntent.UpdateTargetLow(index, it)) },
                onHighChange = { viewModel.onIntent(ProfileIntent.UpdateTargetHigh(index, it)) },
            )
            Spacer(modifier = Modifier.height(8.dp))
        }

        if (state.targetSegments.size < InsulinProfile.MAX_TARGET_SEGMENTS) {
            TextButton(
                onClick = { viewModel.onIntent(ProfileIntent.AddTargetSegment) },
                modifier = Modifier.semantics {
                    contentDescription = "Add another time segment"
                },
            ) {
                Text(
                    text = stringResource(R.string.onboarding_profile_add_segment),
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = stringResource(R.string.onboarding_profile_target_low_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = stringResource(R.string.onboarding_profile_target_high_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(modifier = Modifier.height(16.dp))
    }
}

// ────────────────────────────────────────────────────────────────
// Sub-Step 5: Basal Program
// ────────────────────────────────────────────────────────────────

/**
 * Basal program sub-step: name input plus rate segments.
 */
@Composable
private fun BasalSubStep(
    state: ProfileState,
    viewModel: ProfileViewModel,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
    ) {
        Text(
            text = stringResource(R.string.onboarding_profile_basal_title),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = stringResource(R.string.onboarding_profile_basal_description),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(modifier = Modifier.height(12.dp))

        // Info banner
        Text(
            text = stringResource(R.string.onboarding_profile_basal_info),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    shape = RoundedCornerShape(8.dp),
                )
                .padding(12.dp),
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Program name
        Text(
            text = stringResource(R.string.onboarding_profile_basal_name_label),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(modifier = Modifier.height(4.dp))

        OutlinedTextField(
            value = state.basalName,
            onValueChange = { if (it.length <= 20) viewModel.onIntent(ProfileIntent.UpdateBasalName(it)) },
            singleLine = true,
            isError = state.basalNameError != null,
            supportingText = state.basalNameError?.let { error ->
                { Text(text = error, color = MaterialTheme.colorScheme.error) }
            },
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
            ),
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = stringResource(R.string.onboarding_profile_time_segments),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(modifier = Modifier.height(8.dp))

        state.basalSegments.forEachIndexed { index, segment ->
            SingleValueSegmentCard(
                segment = segment,
                label = stringResource(R.string.onboarding_profile_basal_rate_label),
                suffix = stringResource(R.string.onboarding_profile_basal_rate_suffix),
                keyboardType = KeyboardType.Decimal,
                onValueChange = { viewModel.onIntent(ProfileIntent.UpdateBasalSegment(index, it)) },
            )
            Spacer(modifier = Modifier.height(8.dp))
        }

        if (state.basalSegments.size < InsulinProfile.MAX_BASAL_SEGMENTS) {
            TextButton(
                onClick = { viewModel.onIntent(ProfileIntent.AddBasalSegment) },
                modifier = Modifier.semantics {
                    contentDescription = "Add another time segment"
                },
            ) {
                Text(
                    text = stringResource(R.string.onboarding_profile_add_segment),
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = stringResource(R.string.onboarding_profile_basal_range_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(modifier = Modifier.height(4.dp))

        // Total daily basal (live region)
        Text(
            text = stringResource(
                R.string.onboarding_profile_basal_total_daily,
                String.format("%.2f", state.totalDailyBasal),
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
        )

        Spacer(modifier = Modifier.height(16.dp))
    }
}

// ────────────────────────────────────────────────────────────────
// Reusable segment cards
// ────────────────────────────────────────────────────────────────

/**
 * Card for a single-value time segment (IC ratio, CF, basal rate).
 */
@Composable
private fun SingleValueSegmentCard(
    segment: SegmentInput,
    label: String,
    suffix: String,
    keyboardType: KeyboardType = KeyboardType.Number,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val timeRange = formatTimeRange(segment.startTime, segment.endTime)
    val segmentA11y = "$timeRange, $label ${segment.value} $suffix"

    Column(
        modifier = modifier
            .fillMaxWidth()
            .border(
                width = 1.dp,
                color = if (segment.error != null) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.outlineVariant,
                shape = RoundedCornerShape(12.dp),
            )
            .background(
                color = MaterialTheme.colorScheme.surfaceContainer,
                shape = RoundedCornerShape(12.dp),
            )
            .padding(16.dp)
            .semantics { contentDescription = segmentA11y },
    ) {
        Text(
            text = timeRange,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(modifier = Modifier.height(8.dp))

        Row(
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )

            Spacer(modifier = Modifier.width(8.dp))

            OutlinedTextField(
                value = segment.value,
                onValueChange = onValueChange,
                singleLine = true,
                isError = segment.error != null,
                keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
                modifier = Modifier.width(100.dp),
                textStyle = MaterialTheme.typography.bodyLarge,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                    errorBorderColor = MaterialTheme.colorScheme.error,
                ),
            )

            Spacer(modifier = Modifier.width(8.dp))

            Text(
                text = suffix,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (segment.error != null) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = segment.error,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

/**
 * Card for a target glucose time segment with dual inputs (low/high).
 */
@Composable
private fun TargetSegmentCard(
    segment: TargetSegmentInput,
    onLowChange: (String) -> Unit,
    onHighChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val timeRange = formatTimeRange(segment.startTime, segment.endTime)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .border(
                width = 1.dp,
                color = if (segment.lowError != null || segment.highError != null) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.outlineVariant
                },
                shape = RoundedCornerShape(12.dp),
            )
            .background(
                color = MaterialTheme.colorScheme.surfaceContainer,
                shape = RoundedCornerShape(12.dp),
            )
            .padding(16.dp),
    ) {
        Text(
            text = timeRange,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(modifier = Modifier.height(8.dp))

        Row(
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.onboarding_profile_target_low),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )

            Spacer(modifier = Modifier.width(8.dp))

            OutlinedTextField(
                value = segment.lowValue,
                onValueChange = onLowChange,
                singleLine = true,
                isError = segment.lowError != null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.width(80.dp),
                textStyle = MaterialTheme.typography.bodyLarge,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                    errorBorderColor = MaterialTheme.colorScheme.error,
                ),
            )

            Text(
                text = " \u2013 ",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            OutlinedTextField(
                value = segment.highValue,
                onValueChange = onHighChange,
                singleLine = true,
                isError = segment.highError != null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.width(80.dp),
                textStyle = MaterialTheme.typography.bodyLarge,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                    errorBorderColor = MaterialTheme.colorScheme.error,
                ),
            )

            Spacer(modifier = Modifier.width(8.dp))

            Text(
                text = stringResource(R.string.onboarding_profile_target_high),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = stringResource(R.string.onboarding_profile_target_unit),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )

        if (segment.lowError != null) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = segment.lowError,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        if (segment.highError != null) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = segment.highError,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

/**
 * Format a time range for display.
 *
 * @param start Start time.
 * @param end End time.
 * @return Formatted time range string.
 */
private fun formatTimeRange(start: java.time.LocalTime, end: java.time.LocalTime): String {
    return if (start == java.time.LocalTime.MIDNIGHT && end == java.time.LocalTime.MIDNIGHT) {
        "12:00 AM \u2013 11:59 PM"
    } else {
        "${start.format(TIME_FORMAT)} \u2013 ${end.format(TIME_FORMAT)}"
    }
}
