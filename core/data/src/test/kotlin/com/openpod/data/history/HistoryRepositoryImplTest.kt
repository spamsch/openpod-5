package com.openpod.data.history

import com.google.common.truth.Truth.assertThat
import com.openpod.core.database.dao.HistoryEventDao
import com.openpod.core.database.entity.HistoryEventEntity
import com.openpod.model.history.HistoryEventType
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * Tests for [HistoryRepositoryImpl] — maps between Room entities and domain models.
 *
 * Verifies:
 * - Flow emission from observeAll and observeByType
 * - Entity-to-domain mapping with valid and unknown event types
 * - Event recording delegates to DAO correctly
 */
class HistoryRepositoryImplTest {

    private val dao: HistoryEventDao = mockk()
    private val repo = HistoryRepositoryImpl(dao)

    private val now = Instant.parse("2026-04-01T12:00:00Z")

    private fun entity(
        id: Long = 1,
        type: String = "BOLUS",
        primaryValue: Double = 3.0,
    ) = HistoryEventEntity(
        id = id,
        eventType = type,
        timestamp = now,
        primaryValue = primaryValue,
        secondaryValue = null,
        metadata = null,
    )

    @Test
    fun `observeAllEvents maps entities to domain models`() = runTest {
        every { dao.observeAll() } returns flowOf(
            listOf(
                entity(id = 1, type = "BOLUS", primaryValue = 3.0),
                entity(id = 2, type = "GLUCOSE", primaryValue = 120.0),
            ),
        )

        val events = repo.observeAllEvents().first()
        assertThat(events).hasSize(2)
        assertThat(events[0].type).isEqualTo(HistoryEventType.BOLUS)
        assertThat(events[0].primaryValue).isEqualTo(3.0)
        assertThat(events[1].type).isEqualTo(HistoryEventType.GLUCOSE)
    }

    @Test
    fun `observeEventsByType filters by type`() = runTest {
        every { dao.observeByType("BOLUS") } returns flowOf(
            listOf(entity(id = 1, type = "BOLUS")),
        )

        val events = repo.observeEventsByType(HistoryEventType.BOLUS).first()
        assertThat(events).hasSize(1)
        assertThat(events[0].type).isEqualTo(HistoryEventType.BOLUS)
    }

    @Test
    fun `unknown event type falls back to POD`() = runTest {
        every { dao.observeAll() } returns flowOf(
            listOf(entity(id = 1, type = "UNKNOWN_TYPE")),
        )

        val events = repo.observeAllEvents().first()
        assertThat(events[0].type).isEqualTo(HistoryEventType.POD)
    }

    @Test
    fun `recordEvent inserts entity with correct fields`() = runTest {
        val slot = slot<HistoryEventEntity>()
        coEvery { dao.insert(capture(slot)) } returns 1L

        repo.recordEvent(
            type = HistoryEventType.BOLUS,
            primaryValue = 3.0,
            secondaryValue = "MEAL",
            metadata = """{"carbs":45}""",
        )

        coVerify(exactly = 1) { dao.insert(any()) }
        val captured = slot.captured
        assertThat(captured.eventType).isEqualTo("BOLUS")
        assertThat(captured.primaryValue).isEqualTo(3.0)
        assertThat(captured.secondaryValue).isEqualTo("MEAL")
        assertThat(captured.metadata).isEqualTo("""{"carbs":45}""")
    }

    @Test
    fun `recordEvent with null optional fields`() = runTest {
        coEvery { dao.insert(any()) } returns 2L

        repo.recordEvent(
            type = HistoryEventType.ALERT,
            primaryValue = 1.0,
        )

        coVerify(exactly = 1) { dao.insert(any()) }
    }

    @Test
    fun `all HistoryEventType values are mapped correctly`() = runTest {
        val entities = HistoryEventType.entries.mapIndexed { i, type ->
            entity(id = i.toLong() + 1, type = type.name, primaryValue = i.toDouble())
        }
        every { dao.observeAll() } returns flowOf(entities)

        val events = repo.observeAllEvents().first()
        assertThat(events).hasSize(HistoryEventType.entries.size)
        events.forEachIndexed { i, event ->
            assertThat(event.type).isEqualTo(HistoryEventType.entries[i])
        }
    }
}
