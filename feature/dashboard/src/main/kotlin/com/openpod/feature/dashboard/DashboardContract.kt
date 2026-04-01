package com.openpod.feature.dashboard

import com.openpod.core.ui.mvi.UiEffect
import com.openpod.core.ui.mvi.UiIntent
import com.openpod.core.ui.mvi.UiState
import com.openpod.model.glucose.GlucoseReading
import com.openpod.model.insulin.BolusRecord
import com.openpod.model.insulin.InsulinOnBoard
import com.openpod.model.pod.PodState

/**
 * Dashboard UI state rendered by [DashboardScreen].
 *
 * All fields are nullable to represent the "no data yet" state for each
 * metric independently. The screen uses these nulls to show placeholder
 * values ("--") rather than incorrect zeros.
 *
 * @property glucoseReading Latest CGM glucose reading, or null if unavailable.
 * @property iob Current insulin-on-board, or null if unavailable.
 * @property podState Full pod runtime state, or null if no pod is paired.
 * @property lastBolus Most recent completed or in-progress bolus, or null.
 * @property activeBasalRate Current basal delivery rate in U/hr, or null.
 * @property activeBasalProgramName Name of the running basal program, or null in Automatic Mode.
 * @property isLoading True while a refresh operation is in flight.
 * @property isRefreshing True specifically during pull-to-refresh (distinct from initial load).
 */
data class DashboardState(
    val glucoseReading: GlucoseReading? = null,
    val iob: InsulinOnBoard? = null,
    val podState: PodState? = null,
    val lastBolus: BolusRecord? = null,
    val activeBasalRate: Double? = null,
    val activeBasalProgramName: String? = null,
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
) : UiState

/**
 * User intents dispatched from the dashboard UI.
 */
sealed interface DashboardIntent : UiIntent {

    /** Pull-to-refresh or initial data load. */
    data object RefreshData : DashboardIntent

    /** User tapped the Bolus FAB. */
    data object NavigateToBolus : DashboardIntent

    /** User tapped "Pair Pod" in the no-pod banner. */
    data object NavigateToPairing : DashboardIntent

    /** User tapped the PodStatusBar. */
    data object NavigateToPodStatus : DashboardIntent

    /** User tapped the glucose display. */
    data object NavigateToGlucoseDetail : DashboardIntent

    /** User tapped the IOB chip. */
    data object NavigateToIobDetail : DashboardIntent

    /** User tapped the Active Basal card. */
    data object NavigateToBasalPrograms : DashboardIntent

    /** User tapped the mode chip. */
    data object NavigateToModeSettings : DashboardIntent
}

/**
 * One-shot side effects emitted by [DashboardViewModel].
 *
 * Consumed exactly once by the UI to trigger navigation or show transient
 * messages. Never stored in the UI state.
 */
sealed interface DashboardEffect : UiEffect {

    /** Navigate to the Bolus Entry screen. */
    data object OpenBolus : DashboardEffect

    /** Navigate to the Pod Pairing wizard. */
    data object OpenPairing : DashboardEffect

    /** Navigate to the Pod Status screen. */
    data object OpenPodStatus : DashboardEffect

    /** Navigate to the Glucose Detail screen. */
    data object OpenGlucoseDetail : DashboardEffect

    /** Navigate to the IOB Detail screen. */
    data object OpenIobDetail : DashboardEffect

    /** Navigate to the Basal Programs screen. */
    data object OpenBasalPrograms : DashboardEffect

    /** Navigate to the Mode Settings screen. */
    data object OpenModeSettings : DashboardEffect
}
