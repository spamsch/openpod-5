package com.openpod.model.audit

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * Tests for [AuditHasher] — SHA-256 utilities for the audit hash chain.
 *
 * Verifies:
 * - Deterministic hash output
 * - Genesis hash stability
 * - Chain sensitivity (changing any input changes the hash)
 * - Payload hash independence
 */
class AuditHasherTest {

    @Test
    fun `genesis hash is deterministic`() {
        val hash1 = AuditHasher.GENESIS_HASH
        val hash2 = AuditHasher.GENESIS_HASH
        assertThat(hash1).isEqualTo(hash2)
        assertThat(hash1).hasLength(64) // SHA-256 hex = 64 chars
    }

    @Test
    fun `payload hash is deterministic for same input`() {
        val json = """{"units":3.0,"carbs":45}"""
        assertThat(AuditHasher.payloadHash(json)).isEqualTo(AuditHasher.payloadHash(json))
    }

    @Test
    fun `payload hash changes with different input`() {
        val hash1 = AuditHasher.payloadHash("""{"units":3.0}""")
        val hash2 = AuditHasher.payloadHash("""{"units":3.5}""")
        assertThat(hash1).isNotEqualTo(hash2)
    }

    @Test
    fun `chain hash is deterministic`() {
        val hash1 = AuditHasher.chainHash(
            previousEventHash = AuditHasher.GENESIS_HASH,
            category = AuditCategory.BOLUS_REQUEST,
            timestampMillis = 1000L,
            actor = "user",
            payloadJson = """{"units":3.0}""",
        )
        val hash2 = AuditHasher.chainHash(
            previousEventHash = AuditHasher.GENESIS_HASH,
            category = AuditCategory.BOLUS_REQUEST,
            timestampMillis = 1000L,
            actor = "user",
            payloadJson = """{"units":3.0}""",
        )
        assertThat(hash1).isEqualTo(hash2)
    }

    @Test
    fun `chain hash changes when previous hash changes`() {
        val hash1 = AuditHasher.chainHash(
            previousEventHash = AuditHasher.GENESIS_HASH,
            category = AuditCategory.BOLUS_REQUEST,
            timestampMillis = 1000L,
            actor = "user",
            payloadJson = """{"units":3.0}""",
        )
        val hash2 = AuditHasher.chainHash(
            previousEventHash = "0000000000000000000000000000000000000000000000000000000000000000",
            category = AuditCategory.BOLUS_REQUEST,
            timestampMillis = 1000L,
            actor = "user",
            payloadJson = """{"units":3.0}""",
        )
        assertThat(hash1).isNotEqualTo(hash2)
    }

    @Test
    fun `chain hash changes when category changes`() {
        val base = mapOf(
            "prev" to AuditHasher.GENESIS_HASH,
            "ts" to 1000L,
            "actor" to "user",
            "payload" to """{"units":3.0}""",
        )
        val hash1 = AuditHasher.chainHash(base["prev"] as String, AuditCategory.BOLUS_REQUEST, base["ts"] as Long, base["actor"] as String, base["payload"] as String)
        val hash2 = AuditHasher.chainHash(base["prev"] as String, AuditCategory.BOLUS_DISPATCH, base["ts"] as Long, base["actor"] as String, base["payload"] as String)
        assertThat(hash1).isNotEqualTo(hash2)
    }

    @Test
    fun `chain hash changes when timestamp changes`() {
        val hash1 = AuditHasher.chainHash(AuditHasher.GENESIS_HASH, AuditCategory.BOLUS_REQUEST, 1000L, "user", "{}")
        val hash2 = AuditHasher.chainHash(AuditHasher.GENESIS_HASH, AuditCategory.BOLUS_REQUEST, 2000L, "user", "{}")
        assertThat(hash1).isNotEqualTo(hash2)
    }

    @Test
    fun `chain hash changes when actor changes`() {
        val hash1 = AuditHasher.chainHash(AuditHasher.GENESIS_HASH, AuditCategory.BOLUS_REQUEST, 1000L, "user", "{}")
        val hash2 = AuditHasher.chainHash(AuditHasher.GENESIS_HASH, AuditCategory.BOLUS_REQUEST, 1000L, "system", "{}")
        assertThat(hash1).isNotEqualTo(hash2)
    }

    @Test
    fun `record checksum is deterministic`() {
        val cs1 = AuditHasher.recordChecksum(
            category = AuditCategory.BOLUS_COMPLETE,
            timestampMillis = 5000L,
            actor = "pod",
            source = "BolusViewModel",
            clinicalContext = "bolus-123",
            payloadJson = """{"delivered":3.0}""",
            payloadHash = AuditHasher.payloadHash("""{"delivered":3.0}"""),
            previousEventHash = AuditHasher.GENESIS_HASH,
        )
        val cs2 = AuditHasher.recordChecksum(
            category = AuditCategory.BOLUS_COMPLETE,
            timestampMillis = 5000L,
            actor = "pod",
            source = "BolusViewModel",
            clinicalContext = "bolus-123",
            payloadJson = """{"delivered":3.0}""",
            payloadHash = AuditHasher.payloadHash("""{"delivered":3.0}"""),
            previousEventHash = AuditHasher.GENESIS_HASH,
        )
        assertThat(cs1).isEqualTo(cs2)
        assertThat(cs1).hasLength(64)
    }

    @Test
    fun `record checksum changes when any field changes`() {
        val base = AuditHasher.recordChecksum(
            category = AuditCategory.BOLUS_COMPLETE,
            timestampMillis = 5000L,
            actor = "pod",
            source = "BolusViewModel",
            clinicalContext = "bolus-123",
            payloadJson = """{"delivered":3.0}""",
            payloadHash = AuditHasher.payloadHash("""{"delivered":3.0}"""),
            previousEventHash = AuditHasher.GENESIS_HASH,
        )
        val modified = AuditHasher.recordChecksum(
            category = AuditCategory.BOLUS_COMPLETE,
            timestampMillis = 5000L,
            actor = "pod",
            source = "BolusViewModel",
            clinicalContext = "bolus-456", // changed
            payloadJson = """{"delivered":3.0}""",
            payloadHash = AuditHasher.payloadHash("""{"delivered":3.0}"""),
            previousEventHash = AuditHasher.GENESIS_HASH,
        )
        assertThat(base).isNotEqualTo(modified)
    }

    @Test
    fun `all hashes are lowercase hex`() {
        val hash = AuditHasher.payloadHash("test")
        assertThat(hash).matches("[0-9a-f]{64}")
    }
}
