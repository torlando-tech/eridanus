# event_bridge.py
#
# Tiny shim between Reticulum's python callback contracts and Kotlin
# functional-interface callables coming in from PyRnsBackend. The bulk of
# the per-type wrapping lives in Kotlin (PyRns* classes); this file exists
# only to translate kotlin lambdas into the precise object/function shapes
# Reticulum expects.
#
# Keep this file MINIMAL. Anything more than light callable adaptation
# belongs in Kotlin; trips a planned Detekt rule
# `NoRnsFacadeInPythonBackend` if it grows.

# Neutralize signal.signal BEFORE importing RNS so the SIGINT / SIGTERM
# handlers Reticulum.__init__ unconditionally installs (at Reticulum.py:362)
# don't trip "signal only works in main thread of the main interpreter"
# when we boot Reticulum from a worker thread (PyReticulumService.bootInWorkerThread).
# Both signals are command-line-shutdown concerns that don't apply on
# Android — the Service handles lifecycle. atexit.register still works
# unaffected; that's where Reticulum's actual shutdown hook lives.
import signal
signal.signal = lambda *args, **kwargs: None

import RNS


def announce_handler(kt_cb):
    """Reticulum's Transport.register_announce_handler requires an object
    with `aspect_filter` and `received_announce(destination_hash,
    announced_identity, app_data)`. Wraps a Kotlin
    PyAnnounceCallback (Function3<ByteArray, PyObject?, ByteArray?, Boolean>)
    into that shape."""
    class _Handler:
        aspect_filter = None
        def received_announce(self, destination_hash, announced_identity, app_data):
            return bool(kt_cb.call(bytes(destination_hash),
                                   announced_identity,
                                   bytes(app_data) if app_data is not None else None))
    return _Handler()


def link_callback(kt_cb):
    """Wraps a Kotlin PyLinkCallback as a Python callable suitable for
    Destination.set_link_established_callback / Link.set_link_closed_callback."""
    return lambda link: kt_cb.call(link)


def packet_callback(kt_cb):
    """Reticulum invokes packet callbacks as `cb(data, packet)`. The seam
    drops the Packet object (eridanus never uses it). Coerces the data to
    plain `bytes` so Kotlin gets a ByteArray, not a python bytearray view
    that chaquopy might marshal differently."""
    return lambda data, packet: kt_cb.call(bytes(data))


def resource_callback(kt_cb):
    """Wraps a Kotlin PyResourceCallback. Returns True/False so Reticulum's
    ACCEPT_APP path can decide whether to accept the advertisement."""
    return lambda advertisement: bool(kt_cb.call(advertisement))


def resource_concluded_callback(kt_cb):
    """Wraps a Kotlin PyResourceConcludedCallback."""
    return lambda resource: kt_cb.call(resource)


def reticulum_config_dir(app_files_dir):
    """Writes a minimal Reticulum config that wires a LocalClientInterface
    pointing at 127.0.0.1:37428. Eridanus is a shared-instance client only
    — no transport routing, no hosted interfaces. Returns the configdir
    path for `RNS.Reticulum(configdir=...)`.

    Idempotent: if the config file already exists we leave it alone, so
    user-edits (unlikely for eridanus today but reserved for future) aren't
    clobbered."""
    import os
    configdir = os.path.join(app_files_dir, "reticulum")
    os.makedirs(configdir, exist_ok=True)
    config_path = os.path.join(configdir, "config")
    if not os.path.exists(config_path):
        with open(config_path, "w") as f:
            f.write(
                "[reticulum]\n"
                "  enable_transport = No\n"
                "  share_instance = No\n"
                "  shared_instance_port = 37428\n"
                "  instance_control_port = 37429\n"
                "  panic_on_interface_error = No\n"
                "\n"
                "[logging]\n"
                "  loglevel = 4\n"
                "\n"
                "[interfaces]\n"
                "\n"
                "  [[Default Interface]]\n"
                "    type = AutoInterface\n"
                "    enabled = No\n"
            )
    return configdir


def reticulum_start(configdir):
    """Boots a Reticulum instance in shared-instance-client mode against
    the config under `configdir`. Returns the Reticulum instance."""
    return RNS.Reticulum(configdir=configdir)


def is_connected_to_shared_instance(reticulum):
    """Reticulum exposes this as a property; chaquopy can read it directly
    from Kotlin too, but the helper here lets us guard against attribute
    absence on older RNS versions without leaking the check into Kotlin."""
    return bool(getattr(reticulum, "is_connected_to_shared_instance", False))


def find_local_destination(destination_hash):
    """Reticulum tracks locally registered destinations in
    Transport.destinations (a list) and doesn't expose a hash-lookup helper.
    eridanus uses `findDestination` as `am I locally hosting this hash?`,
    so a linear scan is fine — there is at most one local destination
    (the RrcHub, if hub-hosting is active)."""
    for dest in RNS.Transport.destinations:
        if dest.hash == destination_hash:
            return dest
    return None
