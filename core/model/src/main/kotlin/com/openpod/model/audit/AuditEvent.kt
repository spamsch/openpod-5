package com.openpod.model.audit

import java.time.Instant

/**
 * Category of an audit event, identifying the lifecycle phase or action being recorded.
 *
 * Bolus categories track the full delivery lifecycle from request through completion.
 * Other categories cover pod management, authentication, and system events.
 */
enum class AuditCategory {
    BOLUS_REQUEST,
    BOLUS_PRECONDITION_CHECK,
    BOLUS_DISPATCH,
    BOLUS_ACK,
    BOLUS_PROGRESS,
    BOLUS_COMPLETE,
    BOLUS_CANCEL,
    BOLUS_FAIL,
    BASAL_CHANGE,
    POD_ACTIVATION,
    POD_DEACTIVATION,
    AUTHENTICATION,
    SYSTEM,
}

/**
 * An immutable audit record for a safety-relevant action or state transition.
 *
 * Audit events form a hash chain: each event's [previousEventHash] references the
 * [recordChecksum] of the preceding event. The first event in the chain uses
 * [AuditHasher.GENESIS_HASH] as its previous hash.
 *
 * @property id Stable identifier assigned by the persistence layer.
 * @property category The type of action being recorded.
 * @property timestampUtc When the event occurred (UTC).
 * @property actor Who or what initiated the action (e.g., "user", "system", "pod").
 * @property source Code location or component that produced the event.
 * @property clinicalContext Grouping key tying related events together (e.g., bolus ID).
 * @property payloadJson Canonical sorted-keys JSON with event-specific data.
 * @property payloadHash SHA-256 of [payloadJson].
 * @property previousEventHash [recordChecksum] of the preceding event in the chain.
 * @property recordChecksum SHA-256 of all fields except [id] and [recordChecksum] itself.
 */
data class AuditEvent(
    val id: Long = 0,
    val category: AuditCategory,
    val timestampUtc: Instant,
    val actor: String,
    val source: String,
    val clinicalContext: String,
    val payloadJson: String,
    val payloadHash: String,
    val previousEventHash: String,
    val recordChecksum: String,
)
