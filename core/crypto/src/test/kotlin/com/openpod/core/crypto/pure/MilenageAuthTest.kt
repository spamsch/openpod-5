package com.openpod.core.crypto.pure

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * MILENAGE tests using 3GPP TS 35.208 Test Set 1.
 *
 * These are the official test vectors from the standard.
 */
@DisplayName("MILENAGE (3GPP TS 35.208)")
class MilenageAuthTest {

    // Test Set 1 from 3GPP TS 35.208
    private val k = "465b5ce8b199b49faa5f0a2ee238a6bc".hexToBytes()
    private val op = "cdc202d5123e20f62b6d676ac72cb318".hexToBytes()
    private val rand = "23553cbe9637a89d218ae64dae47bf35".hexToBytes()
    private val sqn = "ff9bb4d0b607".hexToBytes()
    private val amf = "b9b9".hexToBytes()

    private val milenage = MilenageAuth(k, op)

    @Test
    fun `f1 produces correct MAC-A`() {
        val macA = milenage.f1(rand, sqn, amf)
        assertThat(macA).isEqualTo("4a9ffac354dfafb3".hexToBytes())
    }

    @Test
    fun `f2 produces correct XRES`() {
        val xres = milenage.f2(rand)
        assertThat(xres).isEqualTo("a54211d5e3ba50bf".hexToBytes())
    }

    @Test
    fun `f3 produces correct CK`() {
        val ck = milenage.f3(rand)
        assertThat(ck).isEqualTo("b40ba9a3c58b2a05bbf0d987b21bf8cb".hexToBytes())
    }

    @Test
    fun `f4 produces correct IK`() {
        val ik = milenage.f4(rand)
        assertThat(ik).isEqualTo("f769bcd751044604127672711c6d3441".hexToBytes())
    }

    @Test
    fun `f5 produces correct AK`() {
        val ak = milenage.f5(rand)
        assertThat(ak).isEqualTo("aa689c648370".hexToBytes())
    }

    @Test
    fun `generate full auth vector`() {
        val av = milenage.generateAuthVector(rand, sqn, amf)
        assertThat(av.macA).isEqualTo("4a9ffac354dfafb3".hexToBytes())
        assertThat(av.xres).isEqualTo("a54211d5e3ba50bf".hexToBytes())
        assertThat(av.ck).isEqualTo("b40ba9a3c58b2a05bbf0d987b21bf8cb".hexToBytes())
        assertThat(av.ik).isEqualTo("f769bcd751044604127672711c6d3441".hexToBytes())
        assertThat(av.ak).isEqualTo("aa689c648370".hexToBytes())
    }

    @Test
    fun `AUTN construction and validation round-trip`() {
        val av = milenage.generateAuthVector(rand, sqn, amf)
        val autn = MilenageAuth.computeAutn(sqn, av.ak, amf, av.macA)
        assertThat(autn).hasLength(16)

        val result = MilenageAuth.validateAutn(milenage, rand, autn, sqn)
        assertThat(result).isInstanceOf(AuthValidationResult.Success::class.java)

        val success = result as AuthValidationResult.Success
        assertThat(success.xres).isEqualTo(av.xres)
        assertThat(success.ck).isEqualTo(av.ck)
        assertThat(success.ik).isEqualTo(av.ik)
    }

    @Test
    fun `zero OP produces valid output`() {
        val m = MilenageAuth(k) // default OP = zeros
        val av = m.generateAuthVector(rand, sqn, amf)
        assertThat(av.macA).hasLength(8)
        assertThat(av.xres).hasLength(8)
        assertThat(av.ck).hasLength(16)
        assertThat(av.ik).hasLength(16)
        assertThat(av.ak).hasLength(6)
    }

    private fun String.hexToBytes(): ByteArray =
        chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
