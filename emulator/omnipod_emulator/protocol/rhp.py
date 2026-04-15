"""
Text-based RHP (Remote Host Protocol) parser and formatter.

The documented Omnipod 5 RHP protocol uses UTF-8 text commands:

    Request:   [G|S]<TypePrefix><TypeNo>.<AttrNo>[=<Payload>]
    Response:  <TypePrefix><TypeNo>.<AttrNo>=<Value>
    Success:   ES<TypePrefix><TypeNo>.<AttrNo>=0
    Error:     E[G|S]<TypeNo>.<AttrNo>=<ErrorCode>
    Version:   GV (request), V<version> (response)

Type prefixes:
    (empty) - Normal attribute
    L       - Logger
    R       - RHP debug log
    A       - Alarm/Alert
    V       - Version

Multiple commands can be batched with comma separation.

Reference: ~/Projects/personal/omnipod-connector/docs/protocol/04-data-protocol.md
"""

from __future__ import annotations

import enum
import logging
import re
from dataclasses import dataclass

logger = logging.getLogger(__name__)


class RhpAction(enum.Enum):
    """RHP command action."""

    GET = "G"
    SET = "S"


class RhpTypePrefix(enum.Enum):
    """RHP type prefixes."""

    NORMAL = ""
    LOGGER = "L"
    DEBUG = "R"
    ALARM = "A"
    VERSION = "V"
    ENGINEERING = "E"  # observed in v3.1.1 post-auth, e.g. SE255.2=<unix_ts>


class RhpErrorCode(enum.IntEnum):
    """Common RHP error codes."""

    UNKNOWN_TYPE = 1
    UNKNOWN_ATTRIBUTE = 2
    READ_ONLY = 3
    INVALID_VALUE = 4
    INVALID_STATE = 5
    NOT_SUPPORTED = 6


@dataclass
class RhpRequest:
    """
    A parsed RHP text request.

    Examples:
        GV              -> action=GET, type_prefix=VERSION, type_no=None, attr_no=None
        G3.6            -> action=GET, type_prefix=NORMAL, type_no=3, attr_no=6
        GL0.5           -> action=GET, type_prefix=LOGGER, type_no=0, attr_no=5
        S3.9=300        -> action=SET, type_prefix=NORMAL, type_no=3, attr_no=9, payload="300"
        S255.2=17119296 -> action=SET, type_prefix=NORMAL, type_no=255, attr_no=2, payload="17119296"
    """

    action: RhpAction
    type_prefix: RhpTypePrefix = RhpTypePrefix.NORMAL
    type_no: int | None = None
    attr_no: int | None = None
    payload: str | None = None
    raw: str = ""

    @property
    def is_version_request(self) -> bool:
        return self.type_prefix == RhpTypePrefix.VERSION

    @property
    def type_attr_key(self) -> tuple[str, int | None, int | None]:
        """Return (prefix, type_no, attr_no) tuple for handler lookup."""
        return (self.type_prefix.value, self.type_no, self.attr_no)


@dataclass
class RhpResponse:
    """
    An RHP text response.

    For attribute responses:  <prefix><type>.<attr>=<value>
    For success:              ES<prefix><type>.<attr>=0
    For errors:               E[G|S]<type>.<attr>=<code>
    For version:              V<version_string>
    """

    text: str

    @staticmethod
    def attribute(
        type_prefix: str,
        type_no: int,
        attr_no: int,
        value: str,
    ) -> RhpResponse:
        """Build an attribute response: <prefix><type>.<attr>=<value>"""
        return RhpResponse(text=f"{type_prefix}{type_no}.{attr_no}={value}")

    @staticmethod
    def success(
        type_prefix: str,
        type_no: int,
        attr_no: int,
    ) -> RhpResponse:
        """Build a set-success response: ES<prefix><type>.<attr>=0"""
        return RhpResponse(text=f"ES{type_prefix}{type_no}.{attr_no}=0")

    @staticmethod
    def error(
        action: RhpAction,
        type_no: int,
        attr_no: int,
        error_code: int,
    ) -> RhpResponse:
        """Build an error response: E[G|S]<type>.<attr>=<code>"""
        return RhpResponse(text=f"E{action.value}{type_no}.{attr_no}={error_code}")

    @staticmethod
    def version(version_string: str) -> RhpResponse:
        """Build a version response: V<version>"""
        return RhpResponse(text=f"V{version_string}")

    @staticmethod
    def alarm(
        type_no: int,
        attr_no: int,
        value: str,
    ) -> RhpResponse:
        """Build an alarm response: A<type>.<attr>=<value>"""
        return RhpResponse(text=f"A{type_no}.{attr_no}={value}")

    @staticmethod
    def logger_response(
        type_no: int,
        attr_no: int,
        value: str,
    ) -> RhpResponse:
        """Build a logger response: L<type>.<attr>=<value>"""
        return RhpResponse(text=f"L{type_no}.{attr_no}={value}")


# Regex for parsing RHP text requests
# Matches: [G|S][L|R|A|V|E]?<type_no>.<attr_no>[=<payload>]
# Also matches bare GV (get version)
#
# The E (ENGINEERING) prefix was observed in v3.1.1 post-auth traffic
# as the second activation command: SE255.2=<unix_timestamp>. It is
# undocumented in the existing RHP spec; treat as the engineering
# counterpart of the NORMAL attribute namespace for now.
_RHP_PATTERN = re.compile(
    r"^(?P<action>[GS])"
    r"(?P<prefix>[LRAVE])?"
    r"(?:(?P<type>\d+)\.(?P<attr>\d+))?"
    r"(?:=(?P<payload>.+))?$",
    re.DOTALL,
)


def parse_rhp_request(text: str) -> RhpRequest:
    """
    Parse a single RHP text request.

    Args:
        text: The RHP request string (e.g., "GV", "G3.6", "S3.9=300").

    Returns:
        The parsed RhpRequest.

    Raises:
        ValueError: If the text cannot be parsed.
    """
    text = text.strip()

    m = _RHP_PATTERN.match(text)
    if not m:
        raise ValueError(f"Invalid RHP request: {text!r}")

    action = RhpAction(m.group("action"))
    prefix_str = m.group("prefix") or ""

    # Special case: GV = get version
    if prefix_str == "V" and m.group("type") is None:
        return RhpRequest(
            action=action,
            type_prefix=RhpTypePrefix.VERSION,
            raw=text,
        )

    # Map prefix
    prefix_map = {
        "": RhpTypePrefix.NORMAL,
        "L": RhpTypePrefix.LOGGER,
        "R": RhpTypePrefix.DEBUG,
        "A": RhpTypePrefix.ALARM,
        "V": RhpTypePrefix.VERSION,
        "E": RhpTypePrefix.ENGINEERING,
    }
    type_prefix = prefix_map.get(prefix_str, RhpTypePrefix.NORMAL)

    type_no = int(m.group("type")) if m.group("type") is not None else None
    attr_no = int(m.group("attr")) if m.group("attr") is not None else None
    payload = m.group("payload")

    return RhpRequest(
        action=action,
        type_prefix=type_prefix,
        type_no=type_no,
        attr_no=attr_no,
        payload=payload,
        raw=text,
    )


def parse_rhp_batch(text: str) -> list[RhpRequest]:
    """
    Parse a comma-separated batch of RHP requests.

    Args:
        text: One or more RHP requests separated by commas.

    Returns:
        List of parsed RhpRequest objects.
    """
    requests = []
    for part in text.split(","):
        part = part.strip()
        if part:
            requests.append(parse_rhp_request(part))
    return requests


def format_rhp_batch(responses: list[RhpResponse]) -> str:
    """
    Format multiple RHP responses as a comma-separated batch.

    Args:
        responses: List of RhpResponse objects.

    Returns:
        Comma-separated response string.
    """
    return ",".join(r.text for r in responses)
