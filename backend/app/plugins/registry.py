from __future__ import annotations

from dataclasses import dataclass
from threading import RLock
from typing import Iterable

from app.plugins.manifest import PluginManifest, PluginState


class PluginRegistryError(RuntimeError):
    """Raised when a plugin lifecycle operation violates registry policy."""


@dataclass(frozen=True, slots=True)
class PluginRegistration:
    manifest: PluginManifest
    state: PluginState


class PluginRegistry:
    """Thread-safe metadata registry with immutable built-in entries.

    The registry never imports, evaluates, or executes plugin source. Runtime
    loading must later be implemented behind an isolated sandbox and explicit
    capability/permission checks.
    """
    def __init__(self, builtins: Iterable[PluginManifest] = ()) -> None:
        self._lock = RLock()
        self._items: dict[str, PluginRegistration] = {}
        for manifest in builtins:
            if not manifest.builtin:
                raise ValueError("built-in registry entries must set builtin=True")
            if manifest.plugin_id in self._items:
                raise ValueError(f"duplicate built-in plugin: {manifest.plugin_id}")
            self._items[manifest.plugin_id] = PluginRegistration(manifest, PluginState.ENABLED)

    def register(self, manifest: PluginManifest) -> PluginRegistration:
        if manifest.builtin:
            raise PluginRegistryError("custom registration cannot claim built-in status")
        with self._lock:
            if manifest.plugin_id in self._items:
                raise PluginRegistryError(f"plugin already registered: {manifest.plugin_id}")
            item = PluginRegistration(manifest, PluginState.REGISTERED)
            self._items[manifest.plugin_id] = item
            return item

    def mark_validated(self, plugin_id: str) -> PluginRegistration:
        """Record that external static and sandbox checks passed."""
        with self._lock:
            item = self._require(plugin_id)
            if item.manifest.builtin:
                return item
            if item.state not in (PluginState.REGISTERED, PluginState.DISABLED, PluginState.FAILED_VALIDATION):
                raise PluginRegistryError(f"cannot validate plugin from state {item.state.value}")
            updated = PluginRegistration(item.manifest, PluginState.VALIDATED)
            self._items[plugin_id] = updated
            return updated

    def mark_validation_failed(self, plugin_id: str) -> PluginRegistration:
        with self._lock:
            item = self._require(plugin_id)
            if item.manifest.builtin:
                raise PluginRegistryError("built-in plugin validation state is immutable")
            updated = PluginRegistration(item.manifest, PluginState.FAILED_VALIDATION)
            self._items[plugin_id] = updated
            return updated

    def enable(self, plugin_id: str) -> PluginRegistration:
        with self._lock:
            item = self._require(plugin_id)
            if not item.manifest.builtin and item.state is not PluginState.VALIDATED:
                raise PluginRegistryError("plugin must pass validation before enablement")
            updated = PluginRegistration(item.manifest, PluginState.ENABLED)
            self._items[plugin_id] = updated
            return updated

    def disable(self, plugin_id: str) -> PluginRegistration:
        with self._lock:
            item = self._require(plugin_id)
            if item.manifest.builtin:
                raise PluginRegistryError("built-in plugins cannot be disabled")
            updated = PluginRegistration(item.manifest, PluginState.DISABLED)
            self._items[plugin_id] = updated
            return updated

    def remove(self, plugin_id: str) -> None:
        with self._lock:
            item = self._require(plugin_id)
            if item.manifest.builtin:
                raise PluginRegistryError("built-in plugins cannot be removed")
            del self._items[plugin_id]

    def get(self, plugin_id: str) -> PluginRegistration:
        with self._lock:
            return self._require(plugin_id)

    def list(self) -> tuple[PluginRegistration, ...]:
        with self._lock:
            return tuple(self._items[key] for key in sorted(self._items))

    def _require(self, plugin_id: str) -> PluginRegistration:
        try:
            return self._items[plugin_id]
        except KeyError as exc:
            raise PluginRegistryError(f"plugin not found: {plugin_id}") from exc
