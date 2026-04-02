package com.openpod.audit

import com.openpod.core.database.dao.AuditEventDao
import com.openpod.core.database.entity.AuditEventEntity
import com.openpod.domain.audit.AuditRepository
import com.openpod.domain.audit.ChainVerificationResult
import com.openpod.model.audit.AuditCategory
import com.openpod.model.audit.AuditEvent
import com.openpod.model.audit.AuditHasher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Room-backed implementation of the audit repository.
 *
 * Uses a [Mutex] to serialize the read-latest-then-insert sequence,
 * ensuring the hash chain remains consistent under concurrent writes.
 */
@Singleton
class AuditRepositoryImpl @Inject constructor(
    private val dao: AuditEventDao,
) : AuditRepository {

    private val chainMutex = Mutex()

    override suspend fun record(
        category: AuditCategory,
        actor: String,
        source: String,
        clinicalContext: String,
        payloadJson: String,
    ): AuditEvent = chainMutex.withLock {
        val now = Instant.now()
        val timestampMillis = now.toEpochMilli()

        val latest = dao.getLatest()
        val previousHash = latest?.recordChecksum ?: AuditHasher.GENESIS_HASH

        val pHash = AuditHasher.payloadHash(payloadJson)
        val checksum = AuditHasher.recordChecksum(
            category = category,
            timestampMillis = timestampMillis,
            actor = actor,
            source = source,
            clinicalContext = clinicalContext,
            payloadJson = payloadJson,
            payloadHash = pHash,
            previousEventHash = previousHash,
        )

        val entity = AuditEventEntity(
            category = category.name,
            timestampUtc = timestampMillis,
            actor = actor,
            source = source,
            clinicalContext = clinicalContext,
            payloadJson = payloadJson,
            payloadHash = pHash,
            previousEventHash = previousHash,
            recordChecksum = checksum,
        )

        val id = dao.insert(entity)
        Timber.d("Audit event recorded: %s id=%d context=%s", category, id, clinicalContext)

        entity.toDomain(id)
    }

    override fun observeAll(): Flow<List<AuditEvent>> =
        dao.observeAll().map { entities -> entities.map { it.toDomain(it.id) } }

    override fun observeByCategory(category: AuditCategory): Flow<List<AuditEvent>> =
        dao.observeByCategory(category.name).map { entities -> entities.map { it.toDomain(it.id) } }

    override fun observeByClinicalContext(clinicalContext: String): Flow<List<AuditEvent>> =
        dao.observeByClinicalContext(clinicalContext).map { entities -> entities.map { it.toDomain(it.id) } }

    override suspend fun verifyChainIntegrity(): ChainVerificationResult {
        val all = dao.getAll()
        if (all.isEmpty()) return ChainVerificationResult.Empty

        var expectedPreviousHash = AuditHasher.GENESIS_HASH

        for (entity in all) {
            // Verify previous-event hash link
            if (entity.previousEventHash != expectedPreviousHash) {
                return ChainVerificationResult.Tampered(
                    failedAtId = entity.id,
                    reason = "Previous-event hash mismatch: expected $expectedPreviousHash, found ${entity.previousEventHash}",
                )
            }

            // Recompute and verify record checksum
            val recomputed = AuditHasher.recordChecksum(
                category = AuditCategory.valueOf(entity.category),
                timestampMillis = entity.timestampUtc,
                actor = entity.actor,
                source = entity.source,
                clinicalContext = entity.clinicalContext,
                payloadJson = entity.payloadJson,
                payloadHash = entity.payloadHash,
                previousEventHash = entity.previousEventHash,
            )
            if (recomputed != entity.recordChecksum) {
                return ChainVerificationResult.Tampered(
                    failedAtId = entity.id,
                    reason = "Record checksum mismatch: expected $recomputed, found ${entity.recordChecksum}",
                )
            }

            // Verify payload hash
            val recomputedPayloadHash = AuditHasher.payloadHash(entity.payloadJson)
            if (recomputedPayloadHash != entity.payloadHash) {
                return ChainVerificationResult.Tampered(
                    failedAtId = entity.id,
                    reason = "Payload hash mismatch",
                )
            }

            expectedPreviousHash = entity.recordChecksum
        }

        return ChainVerificationResult.Valid(recordCount = all.size.toLong())
    }

    private fun AuditEventEntity.toDomain(assignedId: Long) = AuditEvent(
        id = assignedId,
        category = AuditCategory.valueOf(category),
        timestampUtc = Instant.ofEpochMilli(timestampUtc),
        actor = actor,
        source = source,
        clinicalContext = clinicalContext,
        payloadJson = payloadJson,
        payloadHash = payloadHash,
        previousEventHash = previousEventHash,
        recordChecksum = recordChecksum,
    )
}
