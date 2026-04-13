package com.openpod.domain.audit

import com.openpod.model.audit.AuditCategory
import com.openpod.model.audit.AuditEvent
import kotlinx.coroutines.flow.Flow

/**
 * Repository for the immutable, hash-chained audit trail.
 *
 * All insulin-delivery actions and safety-relevant state transitions
 * are recorded through this interface. Records are append-only —
 * no delete or update operations are exposed.
 */
interface AuditRepository {

    /**
     * Record a new audit event, linking it to the hash chain.
     *
     * The implementation handles hash computation and chain linking
     * internally. Callers provide the semantic content; the repository
     * computes [AuditEvent.payloadHash], [AuditEvent.previousEventHash],
     * and [AuditEvent.recordChecksum].
     *
     * @param category The type of action being recorded.
     * @param actor Who or what initiated the action.
     * @param source Code location or component producing the event.
     * @param clinicalContext Grouping key (e.g., bolus ID) tying related events together.
     * @param payloadJson Canonical sorted-keys JSON with event-specific data.
     * @return The persisted event with its assigned ID and computed hashes.
     */
    suspend fun record(
        category: AuditCategory,
        actor: String,
        source: String,
        clinicalContext: String,
        payloadJson: String,
    ): AuditEvent

    /** Observe all audit events in chain order (oldest first). */
    fun observeAll(): Flow<List<AuditEvent>>

    /** Observe audit events filtered by [category]. */
    fun observeByCategory(category: AuditCategory): Flow<List<AuditEvent>>

    /** Observe audit events sharing a [clinicalContext] (e.g., a bolus ID). */
    fun observeByClinicalContext(clinicalContext: String): Flow<List<AuditEvent>>

    /**
     * Verify the integrity of the entire audit chain.
     *
     * Walks every record from genesis to latest, recomputing checksums
     * and verifying chain links. Returns the verification result.
     */
    suspend fun verifyChainIntegrity(): ChainVerificationResult
}

/** Result of verifying the audit hash chain. */
sealed interface ChainVerificationResult {
    /** The chain is intact — all checksums and links verified. */
    data class Valid(val recordCount: Long) : ChainVerificationResult

    /** The chain is empty (no records). */
    data object Empty : ChainVerificationResult

    /** A record failed verification. */
    data class Tampered(
        val failedAtId: Long,
        val reason: String,
    ) : ChainVerificationResult
}
