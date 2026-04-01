package com.openpod.feature.dashboard

import com.openpod.core.ui.mvi.MviViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import timber.log.Timber
import javax.inject.Inject

/**
 * MVI ViewModel for the dashboard screen.
 *
 * Subscribes to all [DashboardDataSource] flows on initialization and
 * merges them into a single [DashboardState]. Navigation intents are
 * forwarded as one-shot [DashboardEffect]s.
 *
 * The ViewModel owns no domain logic beyond data plumbing; all
 * calculations (IOB decay, glucose freshness) are performed by the
 * model layer or the composable display components.
 */
@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val dataSource: DashboardDataSource,
) : MviViewModel<DashboardState, DashboardIntent, DashboardEffect>(
    initialState = DashboardState(),
) {

    init {
        observeData()
    }

    override fun handleIntent(intent: DashboardIntent) {
        when (intent) {
            DashboardIntent.RefreshData -> onRefresh()
            DashboardIntent.NavigateToBolus -> emitEffect(DashboardEffect.OpenBolus)
            DashboardIntent.NavigateToPairing -> emitEffect(DashboardEffect.OpenPairing)
            DashboardIntent.NavigateToPodStatus -> emitEffect(DashboardEffect.OpenPodStatus)
            DashboardIntent.NavigateToGlucoseDetail -> emitEffect(DashboardEffect.OpenGlucoseDetail)
            DashboardIntent.NavigateToIobDetail -> emitEffect(DashboardEffect.OpenIobDetail)
            DashboardIntent.NavigateToBasalPrograms -> emitEffect(DashboardEffect.OpenBasalPrograms)
            DashboardIntent.NavigateToModeSettings -> emitEffect(DashboardEffect.OpenModeSettings)
        }
    }

    /**
     * Combines all data source flows into a single state update stream.
     *
     * Uses [combine] to merge six independent flows. Whenever any source
     * emits a new value, the state is updated atomically. The [isLoading]
     * flag is cleared after the first emission.
     */
    private fun observeData() {
        launch {
            combine(
                combine(
                    dataSource.observeGlucoseReading(),
                    dataSource.observeInsulinOnBoard(),
                    dataSource.observePodState(),
                ) { glucose, iob, pod -> Triple(glucose, iob, pod) },
                combine(
                    dataSource.observeLastBolus(),
                    dataSource.observeActiveBasalRate(),
                    dataSource.observeActiveBasalProgramName(),
                ) { bolus, basalRate, basalName -> Triple(bolus, basalRate, basalName) },
            ) { (glucose, iob, pod), (bolus, basalRate, basalName) ->
                DashboardState(
                    glucoseReading = glucose,
                    iob = iob,
                    podState = pod,
                    lastBolus = bolus,
                    activeBasalRate = basalRate,
                    activeBasalProgramName = basalName,
                    isLoading = false,
                    isRefreshing = false,
                )
            }.collectLatest { newState ->
                updateState { newState }
            }
        }
    }

    /**
     * Triggers a manual data refresh via the data source.
     *
     * Sets [DashboardState.isRefreshing] to true while the refresh is
     * in flight. The flag is cleared when the data source flows re-emit.
     */
    private fun onRefresh() {
        if (currentState.isRefreshing) {
            Timber.d("Refresh already in progress, skipping")
            return
        }
        updateState { copy(isRefreshing = true) }
        launch {
            try {
                dataSource.refresh()
            } catch (e: Exception) {
                Timber.e(e, "Dashboard refresh failed")
                updateState { copy(isRefreshing = false) }
            }
        }
    }
}
