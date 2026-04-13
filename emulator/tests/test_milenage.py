"""
Tests for the MILENAGE algorithm.

Uses the official 3GPP test vectors from TS 35.208 to verify correctness.

Test Set 1 from TS 35.208, Section 5 is used as the primary reference.
"""

import pytest

from omnipod_emulator.crypto.milenage import (
    Milenage,
    compute_autn,
    validate_autn,
    _xor,
    _rotate_left,
)


# ---------------------------------------------------------------------------
# 3GPP TS 35.208 Test Set 1
# ---------------------------------------------------------------------------
# These are the official MILENAGE test vectors.

TS35208_K = bytes.fromhex("465b5ce8b199b49faa5f0a2ee238a6bc")
TS35208_RAND = bytes.fromhex("23553cbe9637a89d218ae64dae47bf35")
TS35208_OP = bytes.fromhex("cdc202d5123e20f62b6d676ac72cb318")
TS35208_SQN = bytes.fromhex("ff9bb4d0b607")
TS35208_AMF = bytes.fromhex("b9b9")

# Expected outputs from TS 35.208 Test Set 1
TS35208_OPC = bytes.fromhex("cd63cb71954a9f4e48a5994e37a02baf")
TS35208_F1 = bytes.fromhex("4a9ffac354dfafb3")   # MAC-A (f1)
TS35208_F2 = bytes.fromhex("a54211d5e3ba50bf")   # XRES (f2)
TS35208_F3 = bytes.fromhex("b40ba9a3c58b2a05bbf0d987b21bf8cb")  # CK (f3)
TS35208_F4 = bytes.fromhex("f769bcd751044604127672711c6d3441")  # IK (f4)
TS35208_F5 = bytes.fromhex("aa689c648370")       # AK (f5)


class TestMilenageTestSet1:
    """Verify against 3GPP TS 35.208 Test Set 1."""

    @pytest.fixture
    def mil(self) -> Milenage:
        return Milenage(TS35208_K, TS35208_OP)

    def test_opc_derivation(self, mil: Milenage) -> None:
        """OPc should match the expected value."""
        assert mil._opc == TS35208_OPC

    def test_f1_mac_a(self, mil: Milenage) -> None:
        """f1 should produce the correct MAC-A."""
        mac_a = mil.f1(TS35208_RAND, TS35208_SQN, TS35208_AMF)
        assert mac_a == TS35208_F1

    def test_f2_xres(self, mil: Milenage) -> None:
        """f2 should produce the correct XRES."""
        xres = mil.f2(TS35208_RAND)
        assert xres == TS35208_F2

    def test_f3_ck(self, mil: Milenage) -> None:
        """f3 should produce the correct CK."""
        ck = mil.f3(TS35208_RAND)
        assert ck == TS35208_F3

    def test_f4_ik(self, mil: Milenage) -> None:
        """f4 should produce the correct IK."""
        ik = mil.f4(TS35208_RAND)
        assert ik == TS35208_F4

    def test_f5_ak(self, mil: Milenage) -> None:
        """f5 should produce the correct AK."""
        ak = mil.f5(TS35208_RAND)
        assert ak == TS35208_F5

    def test_generate_auth_vector(self, mil: Milenage) -> None:
        """Full auth vector generation should match all expected values."""
        av = mil.generate_auth_vector(TS35208_RAND, TS35208_SQN, TS35208_AMF)
        assert av.mac_a == TS35208_F1
        assert av.xres == TS35208_F2
        assert av.ck == TS35208_F3
        assert av.ik == TS35208_F4
        assert av.ak == TS35208_F5


class TestMilenageWithZeroOp:
    """Test MILENAGE with OP = all-zeros (expected Omnipod 5 configuration)."""

    def test_zero_op_produces_valid_output(self) -> None:
        """With OP=0, the algorithm should still produce 16-byte outputs."""
        k = b"\x01" * 16
        mil = Milenage(k, op=bytes(16))
        rand = b"\x02" * 16
        sqn = b"\x00" * 6
        amf = b"\x00\x00"

        av = mil.generate_auth_vector(rand, sqn, amf)
        assert len(av.mac_a) == 8
        assert len(av.xres) == 8
        assert len(av.ck) == 16
        assert len(av.ik) == 16
        assert len(av.ak) == 6

    def test_default_op_is_omnipod(self) -> None:
        """Omitting OP should default to OMNIPOD_OP."""
        from omnipod_emulator.crypto.milenage import OMNIPOD_OP

        k = b"\x01" * 16
        mil1 = Milenage(k)
        mil2 = Milenage(k, op=OMNIPOD_OP)
        assert mil1._opc == mil2._opc


class TestAutnValidation:
    """Tests for AUTN construction and validation."""

    def test_autn_construction(self) -> None:
        """AUTN should be (SQN XOR AK) || AMF || MAC-A = 16 bytes."""
        sqn = b"\x00\x00\x00\x00\x00\x01"
        ak = b"\xaa\xbb\xcc\xdd\xee\xff"
        amf = b"\x80\x00"
        mac_a = b"\x01\x02\x03\x04\x05\x06\x07\x08"

        autn = compute_autn(sqn, ak, amf, mac_a)
        assert len(autn) == 16
        assert autn[:6] == _xor(sqn, ak)
        assert autn[6:8] == amf
        assert autn[8:] == mac_a

    def test_autn_round_trip_validation(self) -> None:
        """An AUTN generated with MILENAGE should validate successfully."""
        k = b"\xab" * 16
        mil = Milenage(k)
        rand = b"\xcd" * 16
        sqn = b"\x00\x00\x00\x00\x00\x01"
        amf = b"\x80\x00"

        av = mil.generate_auth_vector(rand, sqn, amf)
        autn = compute_autn(sqn, av.ak, amf, av.mac_a)

        valid, xres, ck, ik, ak = validate_autn(mil, rand, autn, sqn)
        assert valid is True
        assert xres == av.xres
        assert ck == av.ck
        assert ik == av.ik


class TestHelpers:
    """Tests for bit-manipulation helpers."""

    def test_xor_equal_length(self) -> None:
        assert _xor(b"\x00\xff", b"\xff\x00") == b"\xff\xff"

    def test_rotate_left_zero(self) -> None:
        block = b"\x01" + b"\x00" * 15
        assert _rotate_left(block, 0) == block

    def test_rotate_left_8(self) -> None:
        block = b"\x80" + b"\x00" * 15
        rotated = _rotate_left(block, 8)
        # 0x80 at byte 0 = bit 127. Shift left 8: bit wraps to bit 7 (byte 15).
        assert rotated == b"\x00" * 15 + b"\x80"

    def test_invalid_key_length(self) -> None:
        with pytest.raises(ValueError, match="16 bytes"):
            Milenage(b"\x00" * 15)
