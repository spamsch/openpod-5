package com.openpod.feature.onboarding.profile

import com.openpod.core.database.dao.BasalProgramDao
import com.openpod.core.database.dao.InsulinProfileDao
import com.openpod.core.database.entity.BasalProgramEntity
import com.openpod.core.database.entity.BasalSegmentEntity
import com.openpod.core.database.entity.CorrectionFactorSegmentEntity
import com.openpod.core.database.entity.IcRatioSegmentEntity
import com.openpod.core.database.entity.InsulinProfileEntity
import com.openpod.core.database.entity.TargetGlucoseSegmentEntity
import com.openpod.core.ui.mvi.MviViewModel
import com.openpod.core.ui.mvi.UiEffect
import com.openpod.core.ui.mvi.UiIntent
import com.openpod.core.ui.mvi.UiState
import com.openpod.model.onboarding.ProfileSubStep
import com.openpod.model.profile.InsulinProfile
import com.openpod.model.profile.InsulinType
import dagger.hilt.android.lifecycle.HiltViewModel
import timber.log.Timber
import java.time.LocalTime
import javax.inject.Inject

// ────────────────────────────────────────────────────────────────
// State
// ────────────────────────────────────────────────────────────────

/**
 * Holds a single time-segmented value with start/end times and a text input.
 *
 * @property startTime Segment start time (inclusive).
 * @property endTime Segment end time (exclusive).
 * @property value Current input text.
 * @property error Optional validation error message.
 */
data class SegmentInput(
    val startTime: LocalTime = LocalTime.MIDNIGHT,
    val endTime: LocalTime = LocalTime.MIDNIGHT,
    val value: String = "",
    val error: String? = null,
)

/**
 * Holds a target glucose time segment with dual inputs (low/high).
 *
 * @property startTime Segment start time (inclusive).
 * @property endTime Segment end time (exclusive).
 * @property lowValue Current low target input text.
 * @property highValue Current high target input text.
 * @property lowError Optional validation error for the low field.
 * @property highError Optional validation error for the high field.
 */
data class TargetSegmentInput(
    val startTime: LocalTime = LocalTime.MIDNIGHT,
    val endTime: LocalTime = LocalTime.MIDNIGHT,
    val lowValue: String = "80",
    val highValue: String = "120",
    val lowError: String? = null,
    val highError: String? = null,
)

/**
 * Complete UI state for the Profile setup screen.
 *
 * @property currentSubStep The currently active sub-step.
 * @property diaValue Current DIA slider value in hours.
 * @property selectedInsulinType Currently selected insulin type preset.
 * @property icSegments IC ratio time segments.
 * @property cfSegments Correction factor time segments.
 * @property targetSegments Target glucose time segments.
 * @property basalName Basal program name input.
 * @property basalSegments Basal rate time segments.
 * @property basalNameError Validation error for basal program name.
 * @property isLoading Whether data is being loaded from the database.
 */
data class ProfileState(
    val currentSubStep: ProfileSubStep = ProfileSubStep.DIA,
    val diaValue: Double = InsulinProfile.DEFAULT_DIA,
    val selectedInsulinType: InsulinType = InsulinType.OTHER,
    val icSegments: List<SegmentInput> = listOf(SegmentInput(value = "10")),
    val cfSegments: List<SegmentInput> = listOf(SegmentInput(value = "40")),
    val targetSegments: List<TargetSegmentInput> = listOf(TargetSegmentInput()),
    val basalName: String = "Program 1",
    val basalSegments: List<SegmentInput> = listOf(SegmentInput(value = "0.80")),
    val basalNameError: String? = null,
    val isLoading: Boolean = false,
) : UiState {
    /** Total daily basal computed from segment rates and durations. */
    val totalDailyBasal: Double
        get() {
            return basalSegments.sumOf { segment ->
                val rate = segment.value.toDoubleOrNull() ?: 0.0
                val startMin = segment.startTime.hour * 60 + segment.startTime.minute
                val endMin = if (segment.endTime == LocalTime.MIDNIGHT) {
                    24 * 60
                } else {
                    segment.endTime.hour * 60 + segment.endTime.minute
                }
                val minutes = if (endMin > startMin) endMin - startMin else (24 * 60 - startMin) + endMin
                rate * (minutes / 60.0)
            }
        }
}

// ────────────────────────────────────────────────────────────────
// Intents
// ────────────────────────────────────────────────────────────────

/** User intents for the Profile setup screen. */
sealed interface ProfileIntent : UiIntent {
    /** Navigate to the next sub-step (validates current first). */
    data object Next : ProfileIntent

    /** Navigate to the previous sub-step. */
    data object Back : ProfileIntent

    /** Tap on a completed wizard step to jump back. */
    data class GoToStep(val step: Int) : ProfileIntent

    // DIA sub-step
    /** DIA slider value changed. */
    data class UpdateDia(val value: Double) : ProfileIntent

    /** Insulin type preset tapped. */
    data class SelectInsulinType(val type: InsulinType) : ProfileIntent

    // IC ratio sub-step
    /** IC segment value changed. */
    data class UpdateIcSegment(val index: Int, val value: String) : ProfileIntent

    /** Add a new IC segment. */
    data object AddIcSegment : ProfileIntent

    // Correction factor sub-step
    /** CF segment value changed. */
    data class UpdateCfSegment(val index: Int, val value: String) : ProfileIntent

    /** Add a new CF segment. */
    data object AddCfSegment : ProfileIntent

    // Target glucose sub-step
    /** Target low value changed. */
    data class UpdateTargetLow(val index: Int, val value: String) : ProfileIntent

    /** Target high value changed. */
    data class UpdateTargetHigh(val index: Int, val value: String) : ProfileIntent

    /** Add a new target segment. */
    data object AddTargetSegment : ProfileIntent

    // Basal sub-step
    /** Basal program name changed. */
    data class UpdateBasalName(val name: String) : ProfileIntent

    /** Basal segment rate changed. */
    data class UpdateBasalSegment(val index: Int, val value: String) : ProfileIntent

    /** Add a new basal segment. */
    data object AddBasalSegment : ProfileIntent
}

// ────────────────────────────────────────────────────────────────
// Effects
// ────────────────────────────────────────────────────────────────

/** One-shot effects for the Profile screen. */
sealed interface ProfileEffect : UiEffect {
    /** Profile setup complete, navigate to PIN screen. */
    data object NavigateToPin : ProfileEffect

    /** Navigate back to the Permissions screen. */
    data object NavigateBack : ProfileEffect
}

// ────────────────────────────────────────────────────────────────
// ViewModel
// ────────────────────────────────────────────────────────────────

/**
 * ViewModel for the insulin profile setup wizard.
 *
 * Manages a 5-sub-step wizard (DIA, IC, CF, Target, Basal) with per-step
 * validation and persistence to Room via [InsulinProfileDao] and [BasalProgramDao].
 *
 * @param insulinProfileDao DAO for insulin profile and segment persistence.
 * @param basalProgramDao DAO for basal program and segment persistence.
 */
@HiltViewModel
class ProfileViewModel @Inject constructor(
    private val insulinProfileDao: InsulinProfileDao,
    private val basalProgramDao: BasalProgramDao,
) : MviViewModel<ProfileState, ProfileIntent, ProfileEffect>(
    initialState = ProfileState(),
) {
    init {
        loadExistingProfile()
    }

    /** Load any previously saved profile data from the database. */
    private fun loadExistingProfile() {
        launch {
            updateState { copy(isLoading = true) }
            try {
                val profile = insulinProfileDao.getProfile()
                if (profile != null) {
                    Timber.d("Loading existing profile: DIA=%s, type=%s", profile.durationOfInsulinAction, profile.insulinType)
                    updateState {
                        copy(
                            diaValue = profile.durationOfInsulinAction,
                            selectedInsulinType = profile.insulinType,
                        )
                    }
                }

                val icSegments = insulinProfileDao.getIcRatioSegments()
                if (icSegments.isNotEmpty()) {
                    updateState {
                        copy(
                            icSegments = icSegments.map { seg ->
                                SegmentInput(
                                    startTime = seg.startTime,
                                    endTime = seg.endTime,
                                    value = seg.ratioGramsPerUnit.toString(),
                                )
                            },
                        )
                    }
                }

                val cfSegments = insulinProfileDao.getCorrectionFactorSegments()
                if (cfSegments.isNotEmpty()) {
                    updateState {
                        copy(
                            cfSegments = cfSegments.map { seg ->
                                SegmentInput(
                                    startTime = seg.startTime,
                                    endTime = seg.endTime,
                                    value = seg.factorMgDlPerUnit.toString(),
                                )
                            },
                        )
                    }
                }

                val targetSegments = insulinProfileDao.getTargetGlucoseSegments()
                if (targetSegments.isNotEmpty()) {
                    updateState {
                        copy(
                            targetSegments = targetSegments.map { seg ->
                                TargetSegmentInput(
                                    startTime = seg.startTime,
                                    endTime = seg.endTime,
                                    lowValue = seg.lowMgDl.toString(),
                                    highValue = seg.highMgDl.toString(),
                                )
                            },
                        )
                    }
                }

                val basalPrograms = basalProgramDao.getAllPrograms()
                if (basalPrograms.isNotEmpty()) {
                    val program = basalPrograms.first()
                    val segments = basalProgramDao.getSegments(program.id)
                    updateState {
                        copy(
                            basalName = program.name,
                            basalSegments = if (segments.isNotEmpty()) {
                                segments.map { seg ->
                                    SegmentInput(
                                        startTime = seg.startTime,
                                        endTime = seg.endTime,
                                        value = String.format("%.2f", seg.rateUnitsPerHour),
                                    )
                                }
                            } else {
                                basalSegments
                            },
                        )
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to load existing profile data")
            } finally {
                updateState { copy(isLoading = false) }
            }
        }
    }

    override fun handleIntent(intent: ProfileIntent) {
        when (intent) {
            ProfileIntent.Next -> handleNext()
            ProfileIntent.Back -> handleBack()
            is ProfileIntent.GoToStep -> handleGoToStep(intent.step)

            is ProfileIntent.UpdateDia -> updateState {
                // Snap to 0.5 increments
                val snapped = (intent.value * 2).toLong() / 2.0
                copy(diaValue = snapped.coerceIn(InsulinProfile.DIA_RANGE))
            }

            is ProfileIntent.SelectInsulinType -> updateState {
                copy(
                    selectedInsulinType = intent.type,
                    diaValue = intent.type.defaultDiaHours,
                )
            }

            is ProfileIntent.UpdateIcSegment -> updateState {
                copy(
                    icSegments = icSegments.toMutableList().also {
                        if (intent.index in it.indices) {
                            it[intent.index] = it[intent.index].copy(value = intent.value, error = null)
                        }
                    },
                )
            }

            ProfileIntent.AddIcSegment -> addSegment(
                currentSegments = currentState.icSegments,
                maxSegments = InsulinProfile.MAX_IC_CF_SEGMENTS,
            ) { segments -> updateState { copy(icSegments = segments) } }

            is ProfileIntent.UpdateCfSegment -> updateState {
                copy(
                    cfSegments = cfSegments.toMutableList().also {
                        if (intent.index in it.indices) {
                            it[intent.index] = it[intent.index].copy(value = intent.value, error = null)
                        }
                    },
                )
            }

            ProfileIntent.AddCfSegment -> addSegment(
                currentSegments = currentState.cfSegments,
                maxSegments = InsulinProfile.MAX_IC_CF_SEGMENTS,
            ) { segments -> updateState { copy(cfSegments = segments) } }

            is ProfileIntent.UpdateTargetLow -> updateState {
                copy(
                    targetSegments = targetSegments.toMutableList().also {
                        if (intent.index in it.indices) {
                            it[intent.index] = it[intent.index].copy(lowValue = intent.value, lowError = null)
                        }
                    },
                )
            }

            is ProfileIntent.UpdateTargetHigh -> updateState {
                copy(
                    targetSegments = targetSegments.toMutableList().also {
                        if (intent.index in it.indices) {
                            it[intent.index] = it[intent.index].copy(highValue = intent.value, highError = null)
                        }
                    },
                )
            }

            ProfileIntent.AddTargetSegment -> addTargetSegment()

            is ProfileIntent.UpdateBasalName -> updateState {
                copy(basalName = intent.name, basalNameError = null)
            }

            is ProfileIntent.UpdateBasalSegment -> updateState {
                copy(
                    basalSegments = basalSegments.toMutableList().also {
                        if (intent.index in it.indices) {
                            it[intent.index] = it[intent.index].copy(value = intent.value, error = null)
                        }
                    },
                )
            }

            ProfileIntent.AddBasalSegment -> addSegment(
                currentSegments = currentState.basalSegments,
                maxSegments = InsulinProfile.MAX_BASAL_SEGMENTS,
            ) { segments -> updateState { copy(basalSegments = segments) } }
        }
    }

    private fun handleNext() {
        val state = currentState
        when (state.currentSubStep) {
            ProfileSubStep.DIA -> {
                saveDiaToDb()
                updateState { copy(currentSubStep = ProfileSubStep.IC_RATIO) }
            }

            ProfileSubStep.IC_RATIO -> {
                if (validateIcSegments()) {
                    saveIcToDb()
                    updateState { copy(currentSubStep = ProfileSubStep.CORRECTION_FACTOR) }
                }
            }

            ProfileSubStep.CORRECTION_FACTOR -> {
                if (validateCfSegments()) {
                    saveCfToDb()
                    updateState { copy(currentSubStep = ProfileSubStep.TARGET_GLUCOSE) }
                }
            }

            ProfileSubStep.TARGET_GLUCOSE -> {
                if (validateTargetSegments()) {
                    saveTargetToDb()
                    updateState { copy(currentSubStep = ProfileSubStep.BASAL_PROGRAM) }
                }
            }

            ProfileSubStep.BASAL_PROGRAM -> {
                if (validateBasal()) {
                    saveBasalToDb()
                    emitEffect(ProfileEffect.NavigateToPin)
                }
            }
        }
    }

    private fun handleBack() {
        val state = currentState
        when (state.currentSubStep) {
            ProfileSubStep.DIA -> emitEffect(ProfileEffect.NavigateBack)
            ProfileSubStep.IC_RATIO -> updateState { copy(currentSubStep = ProfileSubStep.DIA) }
            ProfileSubStep.CORRECTION_FACTOR -> updateState { copy(currentSubStep = ProfileSubStep.IC_RATIO) }
            ProfileSubStep.TARGET_GLUCOSE -> updateState { copy(currentSubStep = ProfileSubStep.CORRECTION_FACTOR) }
            ProfileSubStep.BASAL_PROGRAM -> updateState { copy(currentSubStep = ProfileSubStep.TARGET_GLUCOSE) }
        }
    }

    private fun handleGoToStep(step: Int) {
        val targetSubStep = ProfileSubStep.entries.find { it.stepNumber == step } ?: return
        if (targetSubStep.stepNumber < currentState.currentSubStep.stepNumber) {
            updateState { copy(currentSubStep = targetSubStep) }
        }
    }

    // ── Validation ─────────────────────────────────────────────

    private fun validateIcSegments(): Boolean {
        var valid = true
        val updated = currentState.icSegments.map { seg ->
            val intVal = seg.value.toIntOrNull()
            if (intVal == null || intVal !in InsulinProfile.IC_RATIO_RANGE) {
                valid = false
                seg.copy(error = "1-150")
            } else {
                seg.copy(error = null)
            }
        }
        updateState { copy(icSegments = updated) }
        return valid
    }

    private fun validateCfSegments(): Boolean {
        var valid = true
        val updated = currentState.cfSegments.map { seg ->
            val intVal = seg.value.toIntOrNull()
            if (intVal == null || intVal !in InsulinProfile.CF_RANGE) {
                valid = false
                seg.copy(error = "1-400")
            } else {
                seg.copy(error = null)
            }
        }
        updateState { copy(cfSegments = updated) }
        return valid
    }

    private fun validateTargetSegments(): Boolean {
        var valid = true
        val updated = currentState.targetSegments.map { seg ->
            val low = seg.lowValue.toIntOrNull()
            val high = seg.highValue.toIntOrNull()
            var lowError: String? = null
            var highError: String? = null

            if (low == null || low !in InsulinProfile.TARGET_LOW_RANGE) {
                valid = false
                lowError = "70-150"
            }
            if (high == null || high !in InsulinProfile.TARGET_HIGH_RANGE) {
                valid = false
                highError = "80-200"
            }
            if (low != null && high != null && high < low) {
                valid = false
                highError = "High >= Low"
            }

            seg.copy(lowError = lowError, highError = highError)
        }
        updateState { copy(targetSegments = updated) }
        return valid
    }

    private fun validateBasal(): Boolean {
        var valid = true
        val name = currentState.basalName.trim()
        if (name.isBlank()) {
            updateState { copy(basalNameError = "Required") }
            valid = false
        }

        val updated = currentState.basalSegments.map { seg ->
            val rate = seg.value.toDoubleOrNull()
            if (rate == null || rate !in 0.05..30.0) {
                valid = false
                seg.copy(error = "0.05-30.0")
            } else {
                seg.copy(error = null)
            }
        }
        updateState { copy(basalSegments = updated) }
        return valid
    }

    // ── Persistence ────────────────────────────────────────────

    private fun saveDiaToDb() {
        launch {
            try {
                insulinProfileDao.upsertProfile(
                    InsulinProfileEntity(
                        durationOfInsulinAction = currentState.diaValue,
                        insulinType = currentState.selectedInsulinType,
                    ),
                )
                Timber.i("DIA saved: %s hours (%s)", currentState.diaValue, currentState.selectedInsulinType)
            } catch (e: Exception) {
                Timber.e(e, "Failed to save DIA")
            }
        }
    }

    private fun saveIcToDb() {
        launch {
            try {
                val entities = currentState.icSegments.map { seg ->
                    IcRatioSegmentEntity(
                        profileId = InsulinProfileEntity.SINGLETON_ID,
                        startTime = seg.startTime,
                        endTime = seg.endTime,
                        ratioGramsPerUnit = seg.value.toInt(),
                    )
                }
                insulinProfileDao.replaceIcRatioSegments(entities)
                Timber.i("IC segments saved: %d segments", entities.size)
            } catch (e: Exception) {
                Timber.e(e, "Failed to save IC segments")
            }
        }
    }

    private fun saveCfToDb() {
        launch {
            try {
                val entities = currentState.cfSegments.map { seg ->
                    CorrectionFactorSegmentEntity(
                        profileId = InsulinProfileEntity.SINGLETON_ID,
                        startTime = seg.startTime,
                        endTime = seg.endTime,
                        factorMgDlPerUnit = seg.value.toInt(),
                    )
                }
                insulinProfileDao.replaceCorrectionFactorSegments(entities)
                Timber.i("CF segments saved: %d segments", entities.size)
            } catch (e: Exception) {
                Timber.e(e, "Failed to save CF segments")
            }
        }
    }

    private fun saveTargetToDb() {
        launch {
            try {
                val entities = currentState.targetSegments.map { seg ->
                    TargetGlucoseSegmentEntity(
                        profileId = InsulinProfileEntity.SINGLETON_ID,
                        startTime = seg.startTime,
                        endTime = seg.endTime,
                        lowMgDl = seg.lowValue.toInt(),
                        highMgDl = seg.highValue.toInt(),
                    )
                }
                insulinProfileDao.replaceTargetGlucoseSegments(entities)
                Timber.i("Target segments saved: %d segments", entities.size)
            } catch (e: Exception) {
                Timber.e(e, "Failed to save target segments")
            }
        }
    }

    private fun saveBasalToDb() {
        launch {
            try {
                val program = BasalProgramEntity(
                    name = currentState.basalName.trim(),
                    isActive = true,
                )
                val segments = currentState.basalSegments.map { seg ->
                    BasalSegmentEntity(
                        programId = 0, // Will be overwritten by saveFullProgram
                        startTime = seg.startTime,
                        endTime = seg.endTime,
                        rateUnitsPerHour = seg.value.toDouble(),
                    )
                }
                basalProgramDao.saveFullProgram(program, segments)
                Timber.i("Basal program saved: %s, %d segments", program.name, segments.size)
            } catch (e: Exception) {
                Timber.e(e, "Failed to save basal program")
            }
        }
    }

    // ── Segment helpers ────────────────────────────────────────

    /**
     * Add a new time segment by splitting the last segment at its midpoint.
     */
    private fun addSegment(
        currentSegments: List<SegmentInput>,
        maxSegments: Int,
        applyUpdate: (List<SegmentInput>) -> Unit,
    ) {
        if (currentSegments.size >= maxSegments) {
            Timber.w("Cannot add segment: maximum %d reached", maxSegments)
            return
        }

        val lastSegment = currentSegments.last()
        val startMin = lastSegment.startTime.hour * 60 + lastSegment.startTime.minute
        val endMin = if (lastSegment.endTime == LocalTime.MIDNIGHT) 24 * 60
        else lastSegment.endTime.hour * 60 + lastSegment.endTime.minute
        val totalMin = if (endMin > startMin) endMin - startMin else (24 * 60 - startMin) + endMin

        if (totalMin < 60) {
            Timber.w("Cannot split segment: duration %d minutes too short", totalMin)
            return
        }

        val midMin = (startMin + totalMin / 2) % (24 * 60)
        val midTime = LocalTime.of(midMin / 60, midMin % 60)

        val updated = currentSegments.toMutableList()
        updated[updated.lastIndex] = lastSegment.copy(endTime = midTime)
        updated.add(
            SegmentInput(
                startTime = midTime,
                endTime = lastSegment.endTime,
                value = lastSegment.value,
            ),
        )
        applyUpdate(updated)
    }

    /** Add a new target glucose segment. */
    private fun addTargetSegment() {
        val segments = currentState.targetSegments
        if (segments.size >= InsulinProfile.MAX_TARGET_SEGMENTS) return

        val lastSegment = segments.last()
        val startMin = lastSegment.startTime.hour * 60 + lastSegment.startTime.minute
        val endMin = if (lastSegment.endTime == LocalTime.MIDNIGHT) 24 * 60
        else lastSegment.endTime.hour * 60 + lastSegment.endTime.minute
        val totalMin = if (endMin > startMin) endMin - startMin else (24 * 60 - startMin) + endMin

        if (totalMin < 60) return

        val midMin = (startMin + totalMin / 2) % (24 * 60)
        val midTime = LocalTime.of(midMin / 60, midMin % 60)

        val updated = segments.toMutableList()
        updated[updated.lastIndex] = lastSegment.copy(endTime = midTime)
        updated.add(
            TargetSegmentInput(
                startTime = midTime,
                endTime = lastSegment.endTime,
                lowValue = lastSegment.lowValue,
                highValue = lastSegment.highValue,
            ),
        )
        updateState { copy(targetSegments = updated) }
    }
}
