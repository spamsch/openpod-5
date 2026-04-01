package com.openpod.feature.dashboard

import com.openpod.model.glucose.GlucoseReading
import com.openpod.model.insulin.BolusRecord
import com.openpod.model.insulin.InsulinOnBoard
import com.openpod.model.pod.PodState
import kotlinx.coroutines.flow.Flow

/**
 * Abstraction for fetching dashboard data.
 *
 * Implementations may pull from BLE telemetry, local database, or
 * mock values. The interface uses [Flow]s so that the UI reacts to
 * real-time updates when backed by a live data source.
 *
 * Swap the binding in the Hilt module to switch between
 * [MockDashboardDataSource] (development) and a production implementation.
 */
interface DashboardDataSource {

    /** Observe the latest glucose reading, or null when unavailable. */
    fun observeGlucoseReading(): Flow<GlucoseReading?>

    /** Observe the current insulin-on-board value, or null when unavailable. */
    fun observeInsulinOnBoard(): Flow<InsulinOnBoard?>

    /** Observe the full pod state, or null when no pod is paired. */
    fun observePodState(): Flow<PodState?>

    /** Observe the most recent bolus record, or null when no history exists. */
    fun observeLastBolus(): Flow<BolusRecord?>

    /** Observe the active basal rate in U/hr, or null when unavailable. */
    fun observeActiveBasalRate(): Flow<Double?>

    /** Observe the name of the active basal program, or null in Automatic Mode. */
    fun observeActiveBasalProgramName(): Flow<String?>

    /** Trigger a manual data refresh (e.g., BLE sync). */
    suspend fun refresh()
}
