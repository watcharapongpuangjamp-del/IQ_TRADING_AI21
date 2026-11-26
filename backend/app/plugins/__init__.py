"""Adaptive plugin foundation.

This package defines metadata and lifecycle management only. It deliberately does
not import or execute user-supplied plugin code.
"""

from app.plugins.manifest import PluginManifest, PluginState
from app.plugins.registry import PluginRegistry, PluginRegistryError

__all__ = ["PluginManifest", "PluginState", "PluginRegistry", "PluginRegistryError"]
