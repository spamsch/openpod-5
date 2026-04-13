package com.openpod.feature.onboarding.ready

import com.openpod.core.database.dao.BasalProgramDao
import com.openpod.core.database.dao.InsulinProfileDao
import com.openpod.core.datastore.OpenPodPreferences
import com.openpod.core.ui.mvi.MviViewModel
import com.openpod.core.ui.mvi.UiEffect
import com.openpod.core.ui.mvi.UiIntent
import com.openpod.core.ui.mvi.UiState
import com.openpod.model.onboarding.ProfileSubStep
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.first
import timber.log.Timber
import javax.inject.Inject

/**
 * Summary data for a single ready-screen card.
 *
 * @property label Setting name (e.g., "Duration of Insulin Action").
 * @property value Primary display value (e.g., "4.0 hours").
 * @property details Additional detail lines (e.g., segment summaries).
 */
data class SettingSummary(
    val label: String,
    val value: String,
    val details: List<String> = emptyList(),
)

/**
 * UI state for the Ready (summary) screen.
 *
 * @property diaSummary DIA setting summary.
 * @property icSummary IC ratio setting summary.
 * @property cfSummary Correction factor setting summary.
 * @property targetSummary Target glucose setting summary.
 * @property basalSummary Basal program setting summary.
 * @property pinEnabled Whether a PIN has been configured.
 * @property biometricEnabled Whether biometric auth is enabled.
 * @property isLoading Whether data is being loaded.
 */
data class ReadyState(
    val diaSummary: SettingSummary? = null,
    val icSummary: SettingSummary? = null,
    val cfSummary: SettingSummary? = null,
    val targetSummary: SettingSummary? = null,
    val basalSummary: SettingSummary? = null,
    val pinEnabled: Boolean = false,
    val biometricEnabled: Boolean = false,
    val isLoading: Boolean = true,
) : UiState

/** User intents for the Ready screen. */
sealed interface ReadyIntent : UiIntent {
    /** User tapped "Pair Your First Pod". */
    data object PairPod : ReadyIntent

    /** User tapped "Edit" on a profile setting. */
    data class EditProfile(val subStep: ProfileSubStep) : ReadyIntent

    /** User tapped "Edit" on the PIN card. */
    data object EditPin : ReadyIntent

    /** Reload all data (e.g., after returning from an edit). */
    data object Refresh : ReadyIntent
}

/** One-shot effects for the Ready screen. */
sealed interface ReadyEffect : UiEffect {
    /** Mark onboarding complete and navigate to pod pairing. */
    data object CompleteOnboarding : ReadyEffect

    /** Navigate to the Profile screen at a specific sub-step. */
    data class NavigateToProfile(val subStep: ProfileSubStep) : ReadyEffect

    /** Navigate to the PIN setup screen. */
    data object NavigateToPin : ReadyEffect
}

/**
 * ViewModel for the Ready (summary) screen.
 *
 * Loads all configured profile data from Room and preferences, and displays
 * summary cards. Handles navigation to edit flows and onboarding completion.
 *
 * @param insulinProfileDao DAO for reading insulin profile data.
 * @param basalProgramDao DAO for reading basal program data.
 * @param preferences App preferences for PIN and biometric flags.
 */
@HiltViewModel
class ReadyViewModel @Inject constructor(
    private val insulinProfileDao: InsulinProfileDao,
    private val basalProgramDao: BasalProgramDao,
    private val preferences: OpenPodPreferences,
) : MviViewModel<ReadyState, ReadyIntent, ReadyEffect>(
    initialState = ReadyState(),
) {
    init {
        loadAllData()
    }

    override fun handleIntent(intent: ReadyIntent) {
        when (intent) {
            ReadyIntent.PairPod -> {
                Timber.i("User completing onboarding, proceeding to pod pairing")
                launch {
                    preferences.setOnboardingComplete(true)
                    emitEffect(ReadyEffect.CompleteOnboarding)
                }
            }

            is ReadyIntent.EditProfile -> {
                emitEffect(ReadyEffect.NavigateToProfile(intent.subStep))
            }

            ReadyIntent.EditPin -> {
                emitEffect(ReadyEffect.NavigateToPin)
            }

            ReadyIntent.Refresh -> loadAllData()
        }
    }

    private fun loadAllData() {
        launch {
            updateState { copy(isLoading = true) }
            try {
                // Load DIA
                val profile = insulinProfileDao.getProfile()
                val diaSummary = if (profile != null) {
                    SettingSummary(
                        label = "Duration of Insulin Action",
                        value = String.format("%.1f hours", profile.durationOfInsulinAction),
                    )
                } else {
                    null
                }

                // Load IC segments
                val icSegments = insulinProfileDao.getIcRatioSegments()
                val icSummary = if (icSegments.isNotEmpty()) {
                    val details = icSegments.map { seg ->
                        val timeRange = formatTimeRange(seg.startTime, seg.endTime)
                        "1:${seg.ratioGramsPerUnit} g/U ($timeRange)"
                    }
                    SettingSummary(
                        label = "Insulin-to-Carb Ratio",
                        value = if (icSegments.size == 1) {
                            "1:${icSegments.first().ratioGramsPerUnit} g/U"
                        } else {
                            "${icSegments.size} segments"
                        },
                        details = if (icSegments.size > 1) details else emptyList(),
                    )
                } else {
                    null
                }

                // Load CF segments
                val cfSegments = insulinProfileDao.getCorrectionFactorSegments()
                val cfSummary = if (cfSegments.isNotEmpty()) {
                    val details = cfSegments.map { seg ->
                        val timeRange = formatTimeRange(seg.startTime, seg.endTime)
                        "${seg.factorMgDlPerUnit} mg/dL per U ($timeRange)"
                    }
                    SettingSummary(
                        label = "Correction Factor",
                        value = if (cfSegments.size == 1) {
                            "${cfSegments.first().factorMgDlPerUnit} mg/dL per U"
                        } else {
                            "${cfSegments.size} segments"
                        },
                        details = if (cfSegments.size > 1) details else emptyList(),
                    )
                } else {
                    null
                }

                // Load target segments
                val targetSegments = insulinProfileDao.getTargetGlucoseSegments()
                val targetSummary = if (targetSegments.isNotEmpty()) {
                    val details = targetSegments.map { seg ->
                        val timeRange = formatTimeRange(seg.startTime, seg.endTime)
                        "${seg.lowMgDl} \u2013 ${seg.highMgDl} mg/dL ($timeRange)"
                    }
                    SettingSummary(
                        label = "Target Blood Glucose",
                        value = if (targetSegments.size == 1) {
                            "${targetSegments.first().lowMgDl} \u2013 ${targetSegments.first().highMgDl} mg/dL"
                        } else {
                            "${targetSegments.size} segments"
                        },
                        details = if (targetSegments.size > 1) details else emptyList(),
                    )
                } else {
                    null
                }

                // Load basal program
                val basalPrograms = basalProgramDao.getAllPrograms()
                val basalSummary = if (basalPrograms.isNotEmpty()) {
                    val program = basalPrograms.first()
                    val segments = basalProgramDao.getSegments(program.id)
                    val totalDaily = segments.sumOf { seg ->
                        val startMin = seg.startTime.hour * 60 + seg.startTime.minute
                        val endMin = if (seg.endTime == java.time.LocalTime.MIDNIGHT) {
                            24 * 60
                        } else {
                            seg.endTime.hour * 60 + seg.endTime.minute
                        }
                        val minutes = if (endMin > startMin) endMin - startMin else (24 * 60 - startMin) + endMin
                        seg.rateUnitsPerHour * (minutes / 60.0)
                    }
                    SettingSummary(
                        label = "Basal Program",
                        value = "\"${program.name}\"",
                        details = listOf(
                            if (segments.size == 1) {
                                "${String.format("%.2f", segments.first().rateUnitsPerHour)} U/hr (all day)"
                            } else {
                                "${segments.size} segments"
                            },
                            "${String.format("%.2f", totalDaily)} U total daily",
                        ),
                    )
                } else {
                    null
                }

                // Load PIN/biometric status
                val pinEnabled = preferences.isPinConfigured().first()
                val biometricEnabled = preferences.isBiometricEnabled().first()

                updateState {
                    copy(
                        diaSummary = diaSummary,
                        icSummary = icSummary,
                        cfSummary = cfSummary,
                        targetSummary = targetSummary,
                        basalSummary = basalSummary,
                        pinEnabled = pinEnabled,
                        biometricEnabled = biometricEnabled,
                        isLoading = false,
                    )
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to load summary data")
                updateState { copy(isLoading = false) }
            }
        }
    }

    private fun formatTimeRange(start: java.time.LocalTime, end: java.time.LocalTime): String {
        return if (start == java.time.LocalTime.MIDNIGHT && end == java.time.LocalTime.MIDNIGHT) {
            "all day"
        } else {
            val formatter = java.time.format.DateTimeFormatter.ofPattern("h:mm a")
            "${start.format(formatter)} \u2013 ${end.format(formatter)}"
        }
    }
}
