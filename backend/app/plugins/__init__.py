"""Adaptive plugin metadata and lifecycle foundation.

This package deliberately does not import or execute user-supplied code.
"""
from app.plugins.manifest import PluginManifest, PluginState
from app.plugins.registry import PluginRegistry, PluginRegistryError
from app.plugins.defaults import create_default_plugin_registry

__all__ = [
    "PluginManifest",
    "PluginState",
    "PluginRegistry",
    "PluginRegistryError",
    "create_default_plugin_registry",
]
