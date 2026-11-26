import pytest

from app.plugins import (
    PluginManifest,
    PluginRegistry,
    PluginRegistryError,
    PluginState,
    create_default_plugin_registry,
)


def manifest(plugin_id="broker.iqoption.community", *, builtin=False):
    return PluginManifest(plugin_id, "1.0.0", "Test adapter", ("market.data", "order.submit"), builtin=builtin)


def test_custom_plugin_lifecycle_register_validate_enable_disable_remove():
    registry = PluginRegistry()
    assert registry.register(manifest()).state is PluginState.REGISTERED
    with pytest.raises(PluginRegistryError):
        registry.enable("broker.iqoption.community")
    assert registry.mark_validated("broker.iqoption.community").state is PluginState.VALIDATED
    assert registry.enable("broker.iqoption.community").state is PluginState.ENABLED
    assert registry.disable("broker.iqoption.community").state is PluginState.DISABLED
    registry.remove("broker.iqoption.community")
    with pytest.raises(PluginRegistryError):
        registry.get("broker.iqoption.community")


def test_validation_failure_blocks_activation():
    registry = PluginRegistry()
    registry.register(manifest())
    registry.mark_validation_failed("broker.iqoption.community")
    with pytest.raises(PluginRegistryError):
        registry.enable("broker.iqoption.community")


def test_builtin_plugin_is_enabled_and_immutable():
    registry = PluginRegistry([manifest("core.simulation", builtin=True)])
    assert registry.get("core.simulation").state is PluginState.ENABLED
    with pytest.raises(PluginRegistryError):
        registry.disable("core.simulation")
    with pytest.raises(PluginRegistryError):
        registry.remove("core.simulation")


def test_default_factory_always_provides_simulation():
    item = create_default_plugin_registry().get("core.simulation")
    assert item.manifest.builtin is True
    assert item.state is PluginState.ENABLED
    assert "order.submit.simulation" in item.manifest.capabilities


def test_custom_plugin_cannot_claim_builtin_status():
    with pytest.raises(PluginRegistryError):
        PluginRegistry().register(manifest(builtin=True))


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
