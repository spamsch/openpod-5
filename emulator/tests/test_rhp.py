"""
Unit tests for text RHP parsing, formatting, and dispatch.
"""

from __future__ import annotations

import pytest

from omnipod_emulator.protocol.rhp import (
    RhpAction,
    RhpErrorCode,
    RhpRequest,
    RhpResponse,
    RhpTypePrefix,
    format_rhp_batch,
    parse_rhp_batch,
    parse_rhp_request,
)
from omnipod_emulator.protocol.rhp_dispatcher import RhpTextDispatcher


class TestParseRhpRequest:
    """Test single RHP request parsing."""

    def test_get_version(self):
        req = parse_rhp_request("GV")
        assert req.action == RhpAction.GET
        assert req.type_prefix == RhpTypePrefix.VERSION
        assert req.type_no is None
        assert req.attr_no is None
        assert req.is_version_request

    def test_get_attribute(self):
        req = parse_rhp_request("G3.6")
        assert req.action == RhpAction.GET
        assert req.type_prefix == RhpTypePrefix.NORMAL
        assert req.type_no == 3
        assert req.attr_no == 6
        assert req.payload is None

    def test_set_attribute(self):
        req = parse_rhp_request("S3.9=300")
        assert req.action == RhpAction.SET
        assert req.type_prefix == RhpTypePrefix.NORMAL
        assert req.type_no == 3
        assert req.attr_no == 9
        assert req.payload == "300"

    def test_get_logger(self):
        req = parse_rhp_request("GL0.5")
        assert req.action == RhpAction.GET
        assert req.type_prefix == RhpTypePrefix.LOGGER
        assert req.type_no == 0
        assert req.attr_no == 5

    def test_set_logger(self):
        req = parse_rhp_request("SL0.4=t")
        assert req.action == RhpAction.SET
        assert req.type_prefix == RhpTypePrefix.LOGGER
        assert req.type_no == 0
        assert req.attr_no == 4
        assert req.payload == "t"

    def test_set_utc(self):
        req = parse_rhp_request("S255.2=1711929600")
        assert req.action == RhpAction.SET
        assert req.type_no == 255
        assert req.attr_no == 2
        assert req.payload == "1711929600"

    def test_get_alarm(self):
        req = parse_rhp_request("GA1.0")
        assert req.action == RhpAction.GET
        assert req.type_prefix == RhpTypePrefix.ALARM
        assert req.type_no == 1
        assert req.attr_no == 0

    def test_invalid_request_raises(self):
        with pytest.raises(ValueError):
            parse_rhp_request("INVALID")

    def test_whitespace_stripped(self):
        req = parse_rhp_request("  GV  ")
        assert req.is_version_request


class TestParseRhpBatch:
    """Test comma-separated batch parsing."""

    def test_single_command(self):
        reqs = parse_rhp_batch("GV")
        assert len(reqs) == 1
        assert reqs[0].is_version_request

    def test_multiple_commands(self):
        reqs = parse_rhp_batch("GV,G3.6,S3.9=300")
        assert len(reqs) == 3
        assert reqs[0].is_version_request
        assert reqs[1].type_no == 3
        assert reqs[1].attr_no == 6
        assert reqs[2].payload == "300"

    def test_empty_parts_skipped(self):
        reqs = parse_rhp_batch("GV,,G3.6")
        assert len(reqs) == 2


class TestRhpResponse:
    """Test RHP response formatting."""

    def test_attribute_response(self):
        r = RhpResponse.attribute("", 3, 6, "1;1;120;40")
        assert r.text == "3.6=1;1;120;40"

    def test_success_response(self):
        r = RhpResponse.success("", 3, 9)
        assert r.text == "ES3.9=0"

    def test_success_with_prefix(self):
        r = RhpResponse.success("L", 0, 4)
        assert r.text == "ESL0.4=0"

    def test_error_get(self):
        r = RhpResponse.error(RhpAction.GET, 99, 99, RhpErrorCode.NOT_SUPPORTED)
        assert r.text == "EG99.99=6"

    def test_error_set(self):
        r = RhpResponse.error(RhpAction.SET, 1, 0, RhpErrorCode.INVALID_VALUE)
        assert r.text == "ES1.0=4"

    def test_version_response(self):
        r = RhpResponse.version("3.1.6")
        assert r.text == "V3.1.6"

    def test_alarm_response(self):
        r = RhpResponse.alarm(1, 0, "low_reservoir")
        assert r.text == "A1.0=low_reservoir"

    def test_logger_response(self):
        r = RhpResponse.logger_response(0, 5, "42")
        assert r.text == "L0.5=42"


class TestFormatRhpBatch:
    """Test batch response formatting."""

    def test_single_response(self):
        result = format_rhp_batch([RhpResponse.version("3.1.6")])
        assert result == "V3.1.6"

    def test_multiple_responses(self):
        result = format_rhp_batch([
            RhpResponse.version("3.1.6"),
            RhpResponse.attribute("", 3, 8, "0"),
        ])
        assert result == "V3.1.6,3.8=0"


class TestRhpTextDispatcher:
    """Test the text RHP dispatcher."""

    def test_version_handler(self):
        d = RhpTextDispatcher()
        d.register_version_handler(
            lambda _: RhpResponse.version("1.2.3")
        )
        result = d.dispatch("GV")
        assert result == "V1.2.3"

    def test_get_handler(self):
        d = RhpTextDispatcher()
        d.register_get(3, 6, lambda _: RhpResponse.attribute("", 3, 6, "42"))
        result = d.dispatch("G3.6")
        assert result == "3.6=42"

    def test_set_handler_returns_success(self):
        d = RhpTextDispatcher()
        d.register_set(3, 9, lambda _: RhpResponse.success("", 3, 9))
        result = d.dispatch("S3.9=300")
        assert result == "ES3.9=0"

    def test_unregistered_returns_error(self):
        d = RhpTextDispatcher()
        result = d.dispatch("G99.99")
        assert result.startswith("EG99.99=")

    def test_batch_dispatch(self):
        d = RhpTextDispatcher()
        d.register_version_handler(lambda _: RhpResponse.version("3.1.6"))
        d.register_get(3, 8, lambda _: RhpResponse.attribute("", 3, 8, "0"))
        result = d.dispatch("GV,G3.8")
        assert result == "V3.1.6,3.8=0"

    def test_logger_prefix_handler(self):
        d = RhpTextDispatcher()
        d.register_get(0, 5, lambda _: RhpResponse.attribute("L", 0, 5, "7"), prefix="L")
        result = d.dispatch("GL0.5")
        assert result == "L0.5=7"

    def test_handler_exception_returns_error(self):
        d = RhpTextDispatcher()

        def bad_handler(_req):
            raise RuntimeError("boom")

        d.register_get(3, 6, bad_handler)
        result = d.dispatch("G3.6")
        assert result.startswith("EG3.6=")

    def test_invalid_text_returns_error(self):
        d = RhpTextDispatcher()
        result = d.dispatch("TOTALLY_INVALID")
        assert result.startswith("EG0.0=")
