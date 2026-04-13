package com.openpod.feature.dashboard

import com.openpod.model.glucose.GlucoseReading
import com.openpod.model.glucose.GlucoseTrend
import com.openpod.model.insulin.BolusRecord
import com.openpod.model.insulin.BolusType
import com.openpod.model.insulin.InsulinOnBoard
import com.openpod.model.pod.OperatingMode
import com.openpod.model.pod.PodConnectionState
import com.openpod.model.pod.PodState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import javax.inject.Inject

/**
 * Mock data source providing realistic fake values for dashboard development.
 *
 * Emits a single snapshot of data on subscription. The [refresh] method
 * simulates a 500 ms network delay before re-emitting the same data.
 * Replace this with the real BLE/database-backed implementation when
 * the data layer is ready.
 */
class MockDashboardDataSource @Inject constructor() : DashboardDataSource {

    private val now = Instant.now()

    private val glucose = MutableStateFlow<GlucoseReading?>(
        GlucoseReading(
            valueMgDl = 142,
            timestamp = now.minusSeconds(120),
            trend = GlucoseTrend.RISING,
        ),
    )

    private val iob = MutableStateFlow<InsulinOnBoard?>(
        InsulinOnBoard(
            totalUnits = 2.45,
            mealIob = 1.80,
            correctionIob = 0.65,
            calculatedAt = now.minusSeconds(60),
        ),
    )

    private val podState = MutableStateFlow<PodState?>(
        PodState(
            uid = "MOCK-POD-001",
            lotNumber = "L12345",
            sequenceNumber = "S67890",
            firmwareVersion = "3.1.6",
            bleFirmwareVersion = "2.0.1",
            activatedAt = now.minus(java.time.Duration.ofHours(33)),
            reservoirUnits = 148.0,
            connectionState = PodConnectionState.CONNECTED,
            operatingMode = OperatingMode.AUTOMATIC,
            lastSyncAt = now.minusSeconds(5),
        ),
    )

    private val lastBolus = MutableStateFlow<BolusRecord?>(
        BolusRecord(
            id = "bolus-mock-001",
            requestedUnits = 3.20,
            deliveredUnits = 3.20,
            bolusType = BolusType.MEAL,
            carbsGrams = 45,
            glucoseMgDl = 138,
            startedAt = todayAt(12, 34),
            completedAt = todayAt(12, 37),
        ),
    )

    private val basalRate = MutableStateFlow<Double?>(0.85)

    private val basalProgramName = MutableStateFlow<String?>("Weekday")

    override fun observeGlucoseReading(): Flow<GlucoseReading?> = glucose.asStateFlow()

    override fun observeInsulinOnBoard(): Flow<InsulinOnBoard?> = iob.asStateFlow()

    override fun observePodState(): Flow<PodState?> = podState.asStateFlow()

    override fun observeLastBolus(): Flow<BolusRecord?> = lastBolus.asStateFlow()

    override fun observeActiveBasalRate(): Flow<Double?> = basalRate.asStateFlow()

    override fun observeActiveBasalProgramName(): Flow<String?> = basalProgramName.asStateFlow()

    override suspend fun refresh() {
        Timber.d("MockDashboardDataSource: simulating refresh")
        delay(SIMULATED_REFRESH_DELAY_MS)
        Timber.d("MockDashboardDataSource: refresh complete")
    }

    companion object {
        private const val SIMULATED_REFRESH_DELAY_MS = 500L

        /**
         * Helper to create an [Instant] at the given hour and minute today.
         */
        private fun todayAt(hour: Int, minute: Int): Instant =
            LocalDate.now()
                .atTime(LocalTime.of(hour, minute))
                .atZone(ZoneId.systemDefault())
                .toInstant()
    }
}
