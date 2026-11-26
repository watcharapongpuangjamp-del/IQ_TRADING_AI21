from __future__ import annotations

from dataclasses import dataclass, field
from enum import Enum
import re
from typing import Tuple


_PLUGIN_ID = re.compile(r"^[a-z][a-z0-9_.-]{2,79}$")
_VERSION = re.compile(r"^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)(?:[-+][0-9A-Za-z.-]+)?$")
_CAPABILITY = re.compile(r"^[a-z][a-z0-9_.-]{1,79}$")


class PluginState(str, Enum):
    REGISTERED = "REGISTERED"
    ENABLED = "ENABLED"
    DISABLED = "DISABLED"
    FAILED_VALIDATION = "FAILED_VALIDATION"


@dataclass(frozen=True, slots=True)
class PluginManifest:
    """Validated plugin metadata; this is not an executable plugin package."""

    plugin_id: str
    version: str
    display_name: str
    capabilities: Tuple[str, ...]
    builtin: bool = False
    description: str = ""
    source_digest_sha256: str | None = None
    metadata: dict[str, str] = field(default_factory=dict)

    def __post_init__(self) -> None:
        if not _PLUGIN_ID.fullmatch(self.plugin_id):
            raise ValueError("plugin_id must be a lowercase stable identifier")
        if not _VERSION.fullmatch(self.version):
            raise ValueError("version must use semantic versioning")
        if not self.display_name.strip():
            raise ValueError("display_name is required")
        if not self.capabilities:
            raise ValueError("at least one capability is required")
        if len(set(self.capabilities)) != len(self.capabilities):
            raise ValueError("capabilities must be unique")
        if any(not _CAPABILITY.fullmatch(item) for item in self.capabilities):
            raise ValueError("capabilities must be lowercase identifiers")
        if self.source_digest_sha256 is not None and not re.fullmatch(r"[a-fA-F0-9]{64}", self.source_digest_sha256):
            raise ValueError("source_digest_sha256 must be a 64-character SHA-256 hex digest")
        if any(not isinstance(k, str) or not isinstance(v, str) for k, v in self.metadata.items()):
            raise ValueError("metadata must contain string keys and values")
