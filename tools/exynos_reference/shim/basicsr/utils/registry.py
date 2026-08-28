"""Minimal `basicsr.utils.registry` shim.

The pinned upstream `srvgg_arch.py` (xinntao/Real-ESRGAN @ a4abfb29) imports
`ARCH_REGISTRY` only to register `SRVGGNetCompact` under a name key. Forward
computation never touches the registry, so a no-op `register()` identity
decorator is sufficient and keeps the upstream architecture source verbatim.
"""


class _ArchiveRegistry:
    def __init__(self):
        self._items = {}

    def register(self, name=None):
        def _register(cls):
            self._items[name or cls.__name__] = cls
            return cls

        return _register

    def get(self, name):
        return self._items.get(name)


ARCH_REGISTRY = _ArchiveRegistry()