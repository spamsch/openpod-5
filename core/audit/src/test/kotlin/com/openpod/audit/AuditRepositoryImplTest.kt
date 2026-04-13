package com.openpod.audit

import com.google.common.truth.Truth.assertThat
import com.openpod.core.database.dao.AuditEventDao
import com.openpod.core.database.entity.AuditEventEntity
import com.openpod.domain.audit.ChainVerificationResult
import com.openpod.model.audit.AuditCategory
import com.openpod.model.audit.AuditHasher
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Tests for [AuditRepositoryImpl] — audit trail persistence with hash chain integrity.
 *
 * Uses a mock DAO to verify:
 * - Hash chain linking (genesis → event 1 → event 2)
 * - Payload hash computation
 * - Record checksum computation
 * - Chain integrity verification (valid, empty, tampered)
 */
class AuditRepositoryImplTest {

    private val dao: AuditEventDao = mockk(relaxed = true)
    private val repo = AuditRepositoryImpl(dao)

    private val storedEvents = mutableListOf<AuditEventEntity>()
    private var nextId = 1L

    @BeforeEach
    fun setup() {
        storedEvents.clear()
        nextId = 1L

        val slot = slot<AuditEventEntity>()
        coEvery { dao.insert(capture(slot)) } answers {
            val entity = slot.captured.copy(id = nextId++)
            storedEvents.add(entity)
            entity.id
        }
        coEvery { dao.getLatest() } answers {
            storedEvents.lastOrNull()
        }
        coEvery { dao.getAll() } answers {
            storedEvents.toList()
        }
        coEvery { dao.count() } answers {
            storedEvents.size.toLong()
        }
    }

    @Test
    fun `first event links to genesis hash`() = runTest {
        val event = repo.record(
            category = AuditCategory.BOLUS_REQUEST,
            actor = "user",
            source = "BolusViewModel",
            clinicalContext = "bolus-1",
            payloadJson = """{"units":3.0}""",
        )
        assertThat(event.previousEventHash).isEqualTo(AuditHasher.GENESIS_HASH)
        assertThat(event.payloadHash).isEqualTo(AuditHasher.payloadHash("""{"units":3.0}"""))
        assertThat(event.recordChecksum).hasLength(64)
        assertThat(event.id).isEqualTo(1)
    }

    @Test
    fun `second event links to first event checksum`() = runTest {
        val first = repo.record(
            category = AuditCategory.BOLUS_REQUEST,
            actor = "user",
            source = "BolusViewModel",
            clinicalContext = "bolus-1",
            payloadJson = """{"units":3.0}""",
        )
        val second = repo.record(
            category = AuditCategory.BOLUS_DISPATCH,
            actor = "system",
            source = "BolusViewModel",
            clinicalContext = "bolus-1",
            payloadJson = """{"units":3.0}""",
        )
        assertThat(second.previousEventHash).isEqualTo(first.recordChecksum)
        assertThat(second.previousEventHash).isNotEqualTo(AuditHasher.GENESIS_HASH)
    }

    @Test
    fun `three events form a valid chain`() = runTest {
        val e1 = repo.record(AuditCategory.BOLUS_REQUEST, "user", "VM", "b1", """{"a":1}""")
        val e2 = repo.record(AuditCategory.BOLUS_PRECONDITION_CHECK, "system", "Validator", "b1", """{"passed":true}""")
        val e3 = repo.record(AuditCategory.BOLUS_DISPATCH, "system", "VM", "b1", """{"units":3.0}""")

        assertThat(e1.previousEventHash).isEqualTo(AuditHasher.GENESIS_HASH)
        assertThat(e2.previousEventHash).isEqualTo(e1.recordChecksum)
        assertThat(e3.previousEventHash).isEqualTo(e2.recordChecksum)
    }

    @Test
    fun `verify chain integrity on valid chain`() = runTest {
        repo.record(AuditCategory.BOLUS_REQUEST, "user", "VM", "b1", """{"a":1}""")
        repo.record(AuditCategory.BOLUS_DISPATCH, "system", "VM", "b1", """{"a":2}""")
        repo.record(AuditCategory.BOLUS_COMPLETE, "pod", "VM", "b1", """{"a":3}""")

        val result = repo.verifyChainIntegrity()
        assertThat(result).isInstanceOf(ChainVerificationResult.Valid::class.java)
        assertThat((result as ChainVerificationResult.Valid).recordCount).isEqualTo(3)
    }

    @Test
    fun `verify chain integrity on empty chain`() = runTest {
        val result = repo.verifyChainIntegrity()
        assertThat(result).isEqualTo(ChainVerificationResult.Empty)
    }

    @Test
    fun `verify chain integrity detects tampered checksum`() = runTest {
        repo.record(AuditCategory.BOLUS_REQUEST, "user", "VM", "b1", """{"a":1}""")
        repo.record(AuditCategory.BOLUS_DISPATCH, "system", "VM", "b1", """{"a":2}""")

        // Tamper with the first record's checksum
        val tampered = storedEvents[0].copy(recordChecksum = "0000000000000000000000000000000000000000000000000000000000000000")
        storedEvents[0] = tampered

        val result = repo.verifyChainIntegrity()
        assertThat(result).isInstanceOf(ChainVerificationResult.Tampered::class.java)
    }

    @Test
    fun `verify chain integrity detects broken chain link`() = runTest {
        repo.record(AuditCategory.BOLUS_REQUEST, "user", "VM", "b1", """{"a":1}""")
        repo.record(AuditCategory.BOLUS_DISPATCH, "system", "VM", "b1", """{"a":2}""")

        // Tamper with the second record's previous-event hash
        val tampered = storedEvents[1].copy(previousEventHash = "deadbeef" + "0".repeat(56))
        storedEvents[1] = tampered

        val result = repo.verifyChainIntegrity()
        assertThat(result).isInstanceOf(ChainVerificationResult.Tampered::class.java)
        assertThat((result as ChainVerificationResult.Tampered).failedAtId).isEqualTo(2)
    }

    @Test
    fun `verify chain integrity detects tampered payload`() = runTest {
        repo.record(AuditCategory.BOLUS_REQUEST, "user", "VM", "b1", """{"units":3.0}""")

        // Tamper with the payload but keep the old payload hash
        val tampered = storedEvents[0].copy(payloadJson = """{"units":99.0}""")
        storedEvents[0] = tampered

        val result = repo.verifyChainIntegrity()
        assertThat(result).isInstanceOf(ChainVerificationResult.Tampered::class.java)
    }

    @Test
    fun `event has correct category and metadata`() = runTest {
        val event = repo.record(
            category = AuditCategory.BOLUS_CANCEL,
            actor = "user",
            source = "BolusViewModel",
            clinicalContext = "bolus-42",
            payloadJson = """{"reason":"user_cancelled"}""",
        )
        assertThat(event.category).isEqualTo(AuditCategory.BOLUS_CANCEL)
        assertThat(event.actor).isEqualTo("user")
        assertThat(event.source).isEqualTo("BolusViewModel")
        assertThat(event.clinicalContext).isEqualTo("bolus-42")
    }
}
