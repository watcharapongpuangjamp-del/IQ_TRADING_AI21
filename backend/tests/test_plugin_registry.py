import pytest

from app.plugins import PluginManifest, PluginRegistry, PluginRegistryError, PluginState


def manifest(plugin_id="broker.iqoption.community", *, builtin=False):
    return PluginManifest(
        plugin_id=plugin_id,
        version="1.0.0",
        display_name="Test adapter",
        capabilities=("market.data", "order.submit"),
        builtin=builtin,
    )


def test_custom_plugin_lifecycle_register_enable_disable_remove():
    registry = PluginRegistry()
    registered = registry.register(manifest())
    assert registered.state is PluginState.REGISTERED
    assert registry.enable("broker.iqoption.community").state is PluginState.ENABLED
    assert registry.disable("broker.iqoption.community").state is PluginState.DISABLED
    registry.remove("broker.iqoption.community")
    with pytest.raises(PluginRegistryError):
        registry.get("broker.iqoption.community")


def test_builtin_plugin_is_enabled_and_immutable():
    registry = PluginRegistry([manifest("core.simulation", builtin=True)])
    assert registry.get("core.simulation").state is PluginState.ENABLED
    with pytest.raises(PluginRegistryError):
        registry.disable("core.simulation")
    with pytest.raises(PluginRegistryError):
        registry.remove("core.simulation")


def test_custom_plugin_cannot_claim_builtin_status():
    registry = PluginRegistry()
    with pytest.raises(PluginRegistryError):
        registry.register(manifest(builtin=True))


def test_duplicate_plugin_is_rejected():
    registry = PluginRegistry()
    registry.register(manifest())
    with pytest.raises(PluginRegistryError):
        registry.register(manifest())


def test_invalid_manifest_is_rejected():
    with pytest.raises(ValueError):
        PluginManifest("Bad ID", "1.0.0", "Bad", ("order.submit",))
    with pytest.raises(ValueError):
        PluginManifest("broker.test", "latest", "Bad version", ("order.submit",))
