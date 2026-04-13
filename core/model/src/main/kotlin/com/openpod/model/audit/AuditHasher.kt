package com.openpod.model.audit

import java.security.MessageDigest

/**
 * Pure-Kotlin SHA-256 utilities for the audit hash chain.
 *
 * The hash chain guarantees tamper detection: modifying any record
 * invalidates its checksum and breaks the chain for all subsequent records.
 */
object AuditHasher {

    /** Well-known genesis hash — the previous-event hash for the first record. */
    val GENESIS_HASH: String = sha256("OPENPOD_AUDIT_GENESIS")

    /**
     * Compute the SHA-256 hash of a payload JSON string.
     *
     * The caller must ensure the JSON uses canonical sorted-keys form
     * so hashes are deterministic regardless of insertion order.
     */
    fun payloadHash(payloadJson: String): String = sha256(payloadJson)

    /**
     * Compute the chain hash linking this event to the previous one.
     *
     * ```
     * SHA-256(previousEventHash || category || timestampMillis || actor || payloadJson)
     * ```
     *
     * @param previousEventHash The [AuditEvent.recordChecksum] of the preceding event,
     *   or [GENESIS_HASH] for the first event in the chain.
     */
    fun chainHash(
        previousEventHash: String,
        category: AuditCategory,
        timestampMillis: Long,
        actor: String,
        payloadJson: String,
    ): String {
        val input = buildString {
            append(previousEventHash)
            append(category.name)
            append(timestampMillis)
            append(actor)
            append(payloadJson)
        }
        return sha256(input)
    }

    /**
     * Compute the record checksum covering all significant fields.
     *
     * This checksum is stored in [AuditEvent.recordChecksum] and serves as
     * both a tamper-detection seal and the chain link for the next event.
     */
    fun recordChecksum(
        category: AuditCategory,
        timestampMillis: Long,
        actor: String,
        source: String,
        clinicalContext: String,
        payloadJson: String,
        payloadHash: String,
        previousEventHash: String,
    ): String {
        val input = buildString {
            append(category.name)
            append(timestampMillis)
            append(actor)
            append(source)
            append(clinicalContext)
            append(payloadJson)
            append(payloadHash)
            append(previousEventHash)
        }
        return sha256(input)
    }

    private fun sha256(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(input.toByteArray(Charsets.UTF_8))
        return hash.joinToString("") { "%02x".format(it) }
    }
}
