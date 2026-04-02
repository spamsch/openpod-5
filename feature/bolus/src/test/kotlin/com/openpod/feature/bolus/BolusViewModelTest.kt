package com.openpod.feature.bolus

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import com.openpod.core.datastore.PinManager
import com.openpod.domain.audit.AuditRepository
import com.openpod.domain.audit.ChainVerificationResult
import com.openpod.domain.bolus.BolusSafetyValidator
import com.openpod.domain.bolus.SafetyFailure
import com.openpod.domain.bolus.ValidationResult
import com.openpod.domain.history.HistoryRepository
import com.openpod.domain.pod.ActivationProgress
import com.openpod.domain.pod.DiscoveredPod
import com.openpod.domain.pod.PodActivationResult
import com.openpod.domain.pod.PodManager
import com.openpod.domain.pod.PrimeProgress
import com.openpod.model.audit.AuditCategory
import com.openpod.model.audit.AuditEvent
import com.openpod.model.history.HistoryEvent
import com.openpod.model.history.HistoryEventType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * Tests for [BolusViewModel] — bolus delivery orchestration with safety gates and audit trail.
 *
 * Uses fakes instead of MockK for interfaces returning `kotlin.Result` (which MockK
 * cannot mock correctly due to inline class boxing). Uses recording fakes for audit
 * and history to verify event writes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BolusViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    // --- Fakes ---

    private val healthyStatus = PodActivationResult(
        uid = "pod-abc",
        reservoir = 100.0,
        expiresAt = Instant.now().plusSeconds(3600),
        firmwareVersion = "1.0.0",
        isActivated = true,
        glucoseMgDl = 120,
        iobUnits = 0.5,
        bolusInProgress = false,
        bolusTotalUnits = 0.0,
        bolusRemainingUnits = 0.0,
    )

    private class FakePodManager(
        var statusResults: MutableList<Result<PodActivationResult>>,
        var sendBolusResult: Result<Unit> = Result.success(Unit),
    ) : PodManager {
        private var statusCallCount = 0
        override suspend fun startScan(): Flow<DiscoveredPod> = emptyFlow()
        override suspend fun stopScan() {}
        override suspend fun connect(podId: String) = Result.success(Unit)
        override suspend fun authenticate() = Result.success(Unit)
        override suspend fun prime(): Flow<PrimeProgress> = emptyFlow()
        override suspend fun insertCannula(): Flow<ActivationProgress> = emptyFlow()
        override suspend fun getStatus(): Result<PodActivationResult> {
            val idx = (statusCallCount++).coerceAtMost(statusResults.lastIndex)
            return statusResults[idx]
        }
        override suspend fun sendBolus(units: Double) = sendBolusResult
        override suspend fun cancelBolus() = Result.success(Unit)
    }

    private class FakePinManager(var verifyResult: Boolean = true) : PinManager {
        override suspend fun storePin(pin: String) {}
        override suspend fun verifyPin(pin: String): Boolean = verifyResult
        override suspend fun clearPin() {}
    }

    private class FakeAuditRepository : AuditRepository {
        val recorded = mutableListOf<AuditCategory>()
        private var nextId = 1L
        override suspend fun record(category: AuditCategory, actor: String, source: String, clinicalContext: String, payloadJson: String): AuditEvent {
            recorded += category
            return AuditEvent(
                id = nextId++, category = category, timestampUtc = Instant.now(),
                actor = actor, source = source, clinicalContext = clinicalContext,
                payloadJson = payloadJson, payloadHash = "ph", previousEventHash = "peh", recordChecksum = "rc",
            )
        }
        override fun observeAll(): Flow<List<AuditEvent>> = emptyFlow()
        override fun observeByCategory(category: AuditCategory): Flow<List<AuditEvent>> = emptyFlow()
        override fun observeByClinicalContext(clinicalContext: String): Flow<List<AuditEvent>> = emptyFlow()
        override suspend fun verifyChainIntegrity(): ChainVerificationResult = ChainVerificationResult.Empty
    }

    private class FakeHistoryRepository : HistoryRepository {
        val recorded = mutableListOf<HistoryEventType>()
        override fun observeAllEvents(): Flow<List<HistoryEvent>> = flowOf(emptyList())
        override fun observeEventsByType(type: HistoryEventType): Flow<List<HistoryEvent>> = flowOf(emptyList())
        override suspend fun recordEvent(type: HistoryEventType, primaryValue: Double, secondaryValue: String?, metadata: String?) {
            recorded += type
        }
    }

    private class FakeSafetyValidator(
        var result: ValidationResult = ValidationResult.Passed(Instant.now()),
    ) {
        fun asBolusSafetyValidator(podManager: PodManager): BolusSafetyValidator {
            // We can't easily override the validate method, so we'll use the real validator
            // when we want it to pass, and manipulate the pod manager to cause failures.
            return BolusSafetyValidator(podManager)
        }
    }

    private lateinit var fakePodManager: FakePodManager
    private lateinit var fakePinManager: FakePinManager
    private lateinit var fakeAuditRepo: FakeAuditRepository
    private lateinit var fakeHistoryRepo: FakeHistoryRepository

    @BeforeEach
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        fakePodManager = FakePodManager(
            statusResults = mutableListOf(Result.success(healthyStatus)),
        )
        fakePinManager = FakePinManager()
        fakeAuditRepo = FakeAuditRepository()
        fakeHistoryRepo = FakeHistoryRepository()
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel(
        safetyValidator: BolusSafetyValidator = BolusSafetyValidator(fakePodManager),
    ) = BolusViewModel(
        podManager = fakePodManager,
        pinManager = fakePinManager,
        safetyValidator = safetyValidator,
        auditRepository = fakeAuditRepo,
        historyRepository = fakeHistoryRepo,
    )

    @Test
    fun `initial state is ENTRY phase`() = runTest {
        val vm = createViewModel()
        assertThat(vm.state.value.phase).isEqualTo(BolusPhase.ENTRY)
    }

    @Test
    fun `NextToReview transitions to REVIEW when canReview is true`() = runTest {
        val vm = createViewModel()
        advanceUntilIdle()

        vm.onIntent(BolusIntent.UpdateUnits("3.00"))
        vm.onIntent(BolusIntent.NextToReview)

        assertThat(vm.state.value.phase).isEqualTo(BolusPhase.REVIEW)
    }

    @Test
    fun `NextToReview does not transition when units are empty`() = runTest {
        val vm = createViewModel()
        vm.onIntent(BolusIntent.NextToReview)
        assertThat(vm.state.value.phase).isEqualTo(BolusPhase.ENTRY)
    }

    @Test
    fun `PIN verification updates authentication state`() = runTest {
        val vm = createViewModel()
        advanceUntilIdle()

        vm.onIntent(BolusIntent.UpdateUnits("3.00"))
        vm.onIntent(BolusIntent.NextToReview)
        vm.onIntent(BolusIntent.UpdatePin("1234"))
        advanceUntilIdle()

        assertThat(vm.state.value.isAuthenticated).isTrue()
    }

    @Test
    fun `wrong PIN sets error flag`() = runTest {
        fakePinManager.verifyResult = false
        val vm = createViewModel()
        advanceUntilIdle()

        vm.onIntent(BolusIntent.UpdatePin("9999"))
        advanceUntilIdle()

        assertThat(vm.state.value.pinError).isTrue()
        assertThat(vm.state.value.isAuthenticated).isFalse()
    }

    @Test
    fun `Deliver without authentication does nothing`() = runTest {
        val vm = createViewModel()
        advanceUntilIdle()

        vm.onIntent(BolusIntent.UpdateUnits("3.00"))
        vm.onIntent(BolusIntent.Deliver)
        advanceUntilIdle()

        assertThat(vm.state.value.phase).isEqualTo(BolusPhase.ENTRY)
    }

    @Test
    fun `safety gate failure blocks delivery and emits effect`() = runTest {
        // Make the pod unreachable so the real validator fails
        fakePodManager = FakePodManager(
            statusResults = mutableListOf(
                Result.success(healthyStatus), // for init fetchCurrentValues
                Result.failure(RuntimeException("BLE timeout")), // for validator
            ),
        )

        val vm = createViewModel()
        advanceUntilIdle()

        vm.effect.test {
            vm.onIntent(BolusIntent.UpdateUnits("3.00"))
            vm.onIntent(BolusIntent.NextToReview)
            vm.onIntent(BolusIntent.UpdatePin("1234"))
            advanceUntilIdle()
            vm.onIntent(BolusIntent.Deliver)
            advanceUntilIdle()

            val effect = awaitItem()
            assertThat(effect).isInstanceOf(BolusEffect.SafetyGateFailure::class.java)
            val failures = (effect as BolusEffect.SafetyGateFailure).failures
            assertThat(failures.any { it is SafetyFailure.PodNotReachable }).isTrue()
        }

        assertThat(vm.state.value.phase).isNotEqualTo(BolusPhase.DELIVERING)
    }

    @Test
    fun `safety gate failure records audit events`() = runTest {
        fakePodManager = FakePodManager(
            statusResults = mutableListOf(
                Result.success(healthyStatus),
                Result.failure(RuntimeException("BLE timeout")),
            ),
        )

        val vm = createViewModel()
        advanceUntilIdle()

        vm.onIntent(BolusIntent.UpdateUnits("3.00"))
        vm.onIntent(BolusIntent.NextToReview)
        vm.onIntent(BolusIntent.UpdatePin("1234"))
        advanceUntilIdle()
        vm.onIntent(BolusIntent.Deliver)
        advanceUntilIdle()

        assertThat(fakeAuditRepo.recorded).contains(AuditCategory.BOLUS_REQUEST)
        assertThat(fakeAuditRepo.recorded).contains(AuditCategory.BOLUS_PRECONDITION_CHECK)
        assertThat(fakeAuditRepo.recorded).contains(AuditCategory.BOLUS_FAIL)
    }

    @Test
    fun `successful delivery records full audit lifecycle`() = runTest {
        // First call: init, second: validator, third: after sendBolus poll → delivery complete
        val completedStatus = healthyStatus.copy(
            bolusInProgress = false,
            bolusTotalUnits = 3.0,
            bolusRemainingUnits = 0.0,
        )
        fakePodManager = FakePodManager(
            statusResults = mutableListOf(
                Result.success(healthyStatus),     // init fetchCurrentValues
                Result.success(healthyStatus),     // validator check
                Result.success(completedStatus),   // poll → delivery complete
            ),
        )

        val vm = createViewModel()
        advanceUntilIdle()

        vm.onIntent(BolusIntent.UpdateUnits("3.00"))
        vm.onIntent(BolusIntent.NextToReview)
        vm.onIntent(BolusIntent.UpdatePin("1234"))
        advanceUntilIdle()
        vm.onIntent(BolusIntent.Deliver)
        advanceUntilIdle()

        assertThat(fakeAuditRepo.recorded).contains(AuditCategory.BOLUS_REQUEST)
        assertThat(fakeAuditRepo.recorded).contains(AuditCategory.BOLUS_PRECONDITION_CHECK)
        assertThat(fakeAuditRepo.recorded).contains(AuditCategory.BOLUS_DISPATCH)
        assertThat(fakeAuditRepo.recorded).contains(AuditCategory.BOLUS_ACK)
    }

    @Test
    fun `successful delivery persists bolus to history`() = runTest {
        val completedStatus = healthyStatus.copy(
            bolusInProgress = false,
            bolusTotalUnits = 3.0,
            bolusRemainingUnits = 0.0,
        )
        fakePodManager = FakePodManager(
            statusResults = mutableListOf(
                Result.success(healthyStatus),
                Result.success(healthyStatus),
                Result.success(completedStatus),
            ),
        )

        val vm = createViewModel()
        advanceUntilIdle()

        vm.onIntent(BolusIntent.UpdateUnits("3.00"))
        vm.onIntent(BolusIntent.NextToReview)
        vm.onIntent(BolusIntent.UpdatePin("1234"))
        advanceUntilIdle()
        vm.onIntent(BolusIntent.Deliver)
        advanceUntilIdle()

        assertThat(fakeHistoryRepo.recorded).contains(HistoryEventType.BOLUS)
    }

    @Test
    fun `sendBolus failure records audit and shows error`() = runTest {
        fakePodManager = FakePodManager(
            statusResults = mutableListOf(
                Result.success(healthyStatus),
                Result.success(healthyStatus),
            ),
            sendBolusResult = Result.failure(RuntimeException("BLE error")),
        )

        val vm = createViewModel()
        advanceUntilIdle()

        vm.effect.test {
            vm.onIntent(BolusIntent.UpdateUnits("3.00"))
            vm.onIntent(BolusIntent.NextToReview)
            vm.onIntent(BolusIntent.UpdatePin("1234"))
            advanceUntilIdle()
            vm.onIntent(BolusIntent.Deliver)
            advanceUntilIdle()

            val effect = awaitItem()
            assertThat(effect).isInstanceOf(BolusEffect.ShowError::class.java)
        }

        assertThat(fakeAuditRepo.recorded).contains(AuditCategory.BOLUS_FAIL)
    }

    @Test
    fun `BackToEntry resets review state`() = runTest {
        val vm = createViewModel()
        advanceUntilIdle()

        vm.onIntent(BolusIntent.UpdateUnits("3.00"))
        vm.onIntent(BolusIntent.NextToReview)
        assertThat(vm.state.value.phase).isEqualTo(BolusPhase.REVIEW)

        vm.onIntent(BolusIntent.BackToEntry)
        assertThat(vm.state.value.phase).isEqualTo(BolusPhase.ENTRY)
        assertThat(vm.state.value.pinText).isEmpty()
        assertThat(vm.state.value.isAuthenticated).isFalse()
    }
}
