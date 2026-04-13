package com.openpod.feature.dashboard

import com.openpod.domain.history.HistoryRepository
import com.openpod.domain.pod.PodActivationResult
import com.openpod.domain.pod.PodManager
import com.openpod.model.history.HistoryEventType
import com.openpod.model.glucose.GlucoseReading
import com.openpod.model.glucose.GlucoseTrend
import com.openpod.model.insulin.BolusRecord
import com.openpod.model.insulin.BolusType
import com.openpod.model.insulin.InsulinOnBoard
import com.openpod.model.pod.OperatingMode
import com.openpod.model.pod.PodConnectionState
import com.openpod.model.pod.PodState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import timber.log.Timber
import java.time.Instant
import javax.inject.Inject

/**
 * Dashboard data source that polls the emulator via [PodManager.getStatus].
 *
 * Queries the pod every 5 seconds and maps the extended status response
 * (glucose, IOB, reservoir, activation time) into dashboard model objects.
 */
class EmulatorDashboardDataSource @Inject constructor(
    private val podManager: PodManager,
    private val historyRepository: HistoryRepository,
) : DashboardDataSource {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val snapshot = MutableStateFlow<PodActivationResult?>(null)
    private val lastBolus = MutableStateFlow<BolusRecord?>(null)
    private var wasBolusing = false
    private var lastGlucoseRecordedAt = 0L

    init {
        scope.launch {
            while (true) {
                poll()
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    private suspend fun poll() {
        val result = podManager.getStatus().getOrNull()
        if (result == null || !result.isActivated) return

        snapshot.value = result

        // Record glucose every 5 minutes (debounce)
        val now = System.currentTimeMillis()
        val glucose = result.glucoseMgDl
        if (glucose != null && now - lastGlucoseRecordedAt >= GLUCOSE_RECORD_INTERVAL_MS) {
            lastGlucoseRecordedAt = now
            try {
                historyRepository.recordEvent(
                    type = HistoryEventType.GLUCOSE,
                    primaryValue = glucose.toDouble(),
                    secondaryValue = trendFromInt(result.glucoseTrend).name,
                )
            } catch (e: Exception) {
                Timber.w(e, "Failed to record glucose event")
            }
        }

        // Detect bolus completion: was delivering → no longer delivering
        if (wasBolusing && !result.bolusInProgress && result.bolusTotalUnits > 0) {
            val delivered = result.bolusTotalUnits - result.bolusRemainingUnits
            val bolusRecord = BolusRecord(
                id = "emulator-${now}",
                requestedUnits = result.bolusTotalUnits,
                deliveredUnits = delivered.coerceAtLeast(0.05),
                bolusType = BolusType.MANUAL,
                startedAt = Instant.now(),
                completedAt = Instant.now(),
            )
            lastBolus.value = bolusRecord
            try {
                historyRepository.recordEvent(
                    type = HistoryEventType.BOLUS,
                    primaryValue = bolusRecord.deliveredUnits,
                    secondaryValue = "%.2f U requested".format(bolusRecord.requestedUnits),
                )
            } catch (e: Exception) {
                Timber.w(e, "Failed to record bolus event")
            }
        }
        wasBolusing = result.bolusInProgress
    }

    override fun observeGlucoseReading(): Flow<GlucoseReading?> = snapshot.map { result ->
        val glucose = result?.glucoseMgDl ?: return@map null
        GlucoseReading(
            valueMgDl = glucose.coerceIn(GlucoseReading.VALID_RANGE),
            timestamp = Instant.now(),
            trend = trendFromInt(result.glucoseTrend),
        )
    }

    override fun observeInsulinOnBoard(): Flow<InsulinOnBoard?> = snapshot.map { result ->
        val iob = result?.iobUnits ?: return@map null
        InsulinOnBoard(totalUnits = iob, calculatedAt = Instant.now())
    }

    override fun observePodState(): Flow<PodState?> = snapshot.map { result ->
        result ?: return@map null
        val activatedAt = if (result.isActivated && result.minutesSinceActivation > 0) {
            Instant.now().minusSeconds(result.minutesSinceActivation.toLong() * 60)
        } else {
            Instant.now()
        }
        PodState(
            uid = result.uid,
            lotNumber = "L00001",
            sequenceNumber = "S00001",
            firmwareVersion = result.firmwareVersion,
            activatedAt = activatedAt,
            reservoirUnits = result.reservoir,
            connectionState = PodConnectionState.CONNECTED,
            operatingMode = OperatingMode.AUTOMATIC,
            lastSyncAt = Instant.now(),
        )
    }

    override fun observeLastBolus(): Flow<BolusRecord?> = lastBolus

    override fun observeActiveBasalRate(): Flow<Double?> = snapshot.map { it?.basalRate }

    override fun observeActiveBasalProgramName(): Flow<String?> = MutableStateFlow("Automatic")

    override suspend fun refresh() { poll() }

    companion object {
        private const val POLL_INTERVAL_MS = 5000L
        private const val GLUCOSE_RECORD_INTERVAL_MS = 5 * 60 * 1000L // 5 minutes

        private fun trendFromInt(trend: Int?): GlucoseTrend = when (trend) {
            2 -> GlucoseTrend.RISING_QUICKLY
            1 -> GlucoseTrend.RISING
            0 -> GlucoseTrend.STEADY
            -1 -> GlucoseTrend.FALLING
            -2 -> GlucoseTrend.FALLING_QUICKLY
            else -> GlucoseTrend.UNKNOWN
        }
    }
}
