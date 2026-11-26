"""Factory for the immutable built-in plugin metadata profile."""
from app.plugins.manifest import PluginManifest
from app.plugins.registry import PluginRegistry


def create_default_plugin_registry() -> PluginRegistry:
    """Return the shipped default profile; no optional plugin is required.

    This registers metadata for the existing SimulationConnector only. Other
    trading services remain part of the core and are not falsely exposed as
    removable plugins.
    """
    simulation = PluginManifest(
        plugin_id="core.simulation",
        version="1.0.0",
        display_name="Built-in Simulation Connector",
        description="Default Practice/testing connector shipped with the backend.",
        capabilities=("market.data", "candles.read", "account.balance.read", "order.submit.simulation", "order.reconcile"),
        builtin=True,
    )
    return PluginRegistry(builtins=(simulation,))
