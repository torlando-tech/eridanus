# SPDX-License-Identifier: MPL-2.0

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


def announce_handler(kt_cb, aspect_filter=None):
    """Reticulum's Transport.register_announce_handler requires an object
    with `aspect_filter` and `received_announce(destination_hash,
    announced_identity, app_data)`. Wraps a Kotlin
    PyAnnounceCallback (Function3<ByteArray, PyObject?, ByteArray?, Boolean>)
    into that shape.

    aspect_filter (e.g. "rrc.hub") scopes which announces RNS delivers:
    Transport.inbound calls received_announce only when the announce's
    destination hash matches hash_from_name_and_identity(aspect_filter,
    announced_identity) (Transport.py:2046). None (the old behaviour) means
    EVERY announce on the network is delivered — which fed arbitrary foreign
    app_data (LXMF, NomadNet, …) into the app's CBOR decoder and could drive
    a multi-GB allocation off a malformed length prefix."""
    class _Handler:
        def received_announce(self, destination_hash, announced_identity, app_data):
            return bool(kt_cb.call(bytes(destination_hash),
                                   announced_identity,
                                   bytes(app_data) if app_data is not None else None))
    h = _Handler()
    h.aspect_filter = aspect_filter
    return h


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


def reticulum_config_dir(app_files_dir, attach_to_shared_instance):
    """Writes a minimal Reticulum config and returns the configdir path for
    `RNS.Reticulum(configdir=...)`.

    Eridanus's python flavor is a shared-instance-CLIENT-only app, but
    upstream RNS has no explicit "client only" mode like reticulum-kt does
    — it only attaches as a client *as a fallback* when `share_instance =
    yes` is set and the LocalServerInterface bind fails because something
    else (Sideband, rnsd, etc.) has 37428 first. So the natural pattern
    for attaching to an existing host is paradoxically to set
    `share_instance = yes` — RNS tries to bind, the bind fails, RNS falls
    through to LocalClientInterface.

    That's only safe when we already know a host is present. If we set
    `share_instance = yes` and *nothing* is on 37428, the bind succeeds
    and we silently become the shared-instance server ourselves —
    exactly the "role inversion" eridanus deliberately wants to avoid (see
    EridanusViewModel.bringUpReticulum). So the caller (PyRnsBackend via
    the EridanusViewModel watchdog) is expected to TCP-probe 37428 first
    and pass the result here.

    No rpc_key written. Eridanus's only RPC-flavored call is the
    `_used_destination_data` LRU touch fired by `Identity.recall`, which
    we sidestep by passing `_no_use=True` in PyRnsIdentityFactory.recall.
    All actual data plane (link establishment, packet send, announce
    dispatch, resource transfer) goes over the LocalClientInterface
    socket and never traverses the RPC channel.

    The file is rewritten on every call so a config change can take
    effect on the next backend restart cycle — eridanus has no
    user-editable Reticulum config surface today.
    """
    import os
    configdir = os.path.join(app_files_dir, "reticulum")
    os.makedirs(configdir, exist_ok=True)
    config_path = os.path.join(configdir, "config")
    share_value = "yes" if attach_to_shared_instance else "no"
    with open(config_path, "w") as f:
        f.write(
            "[reticulum]\n"
            "  enable_transport = no\n"
            f"  share_instance = {share_value}\n"
            "  shared_instance_port = 37428\n"
            "  instance_control_port = 37429\n"
            # CRITICAL on Android: without this, RNS's LocalClientInterface
            # defaults to Unix domain sockets, which don't cross Android app
            # sandbox boundaries — so the python LocalClientInterface would
            # never find Sideband / Carina / rnsd's LocalServerInterface no
            # matter what's bound on the TCP probe port. Forcing TCP is the
            # same fix columba v0.10.x landed; the equivalent kotlin config
            # in rns-android is TCP by default so this only matters for the
            # python flavor.
            "  shared_instance_type = tcp\n"
            "  panic_on_interface_error = no\n"
            "\n"
            "[logging]\n"
            "  loglevel = 4\n"
            "\n"
            "[interfaces]\n"
            "\n"
            "  [[Default Interface]]\n"
            "    type = AutoInterface\n"
            "    enabled = no\n"
        )
    return configdir


def reticulum_start(configdir):
    """Boots a Reticulum instance in shared-instance-client mode against
    the config under `configdir`. Returns the Reticulum instance.

    Resets RNS's class-level state before construction (see
    [reticulum_reset_class_state]). RNS is designed for one-shot CLI use
    where process exit clears everything; in our long-running embedded
    process, the watchdog needs to drive RNS through full teardown →
    bring-up cycles when the shared-instance host topology changes.
    """
    reticulum_reset_class_state()
    return RNS.Reticulum(configdir=configdir)


def reticulum_shutdown(reticulum):
    """Persist Reticulum's in-memory state to disk and detach interfaces,
    then clear class-level state so the next [reticulum_start] cycle isn't
    rejected by RNS's CLI-shaped singleton guards.

    Called from PyReticulumService.onDestroy. The columba v0.10.x
    reticulum_wrapper.py established this teardown order — interfaces
    first, then persist, then clear sentinels — and it's the version that
    survives repeated watchdog cycles.
    """
    if reticulum is None:
        return

    # 1. Detach interfaces so sockets close cleanly. Lets the next
    #    LocalClientInterface bind without colliding with a half-closed
    #    socket from the previous cycle.
    try:
        for iface in list(RNS.Transport.interfaces):
            if hasattr(iface, "detach"):
                try: iface.detach()
                except Exception: pass
    except Exception: pass

    # 2. Persist path/announce/etc. tables to disk so the user's learned
    #    network state survives across restarts (Reticulum loads them
    #    back in __init__ on next boot).
    try: RNS.Transport.persist_data()
    except Exception: pass

    # 3. Run upstream's exit_handler — clears _should_run, voids queues,
    #    runs Identity.exit_handler etc. Safe to call alongside the
    #    explicit cleanup below.
    try: RNS.Reticulum.exit_handler()
    except Exception: pass

    # 4. Clear class-level state. Done here on the shutdown path so the
    #    next start() finds a clean slate; reticulum_start re-asserts
    #    this immediately before construction as belt-and-braces.
    reticulum_reset_class_state()


def reticulum_reset_class_state():
    """Clear class-level state that RNS / Transport / Identity populate
    on init and never reset. Without this, the second
    [reticulum_start] cycle fails with `OSError: Attempt to reinitialise
    Reticulum, when it was already running` (from the Reticulum singleton
    sentinel) and then `KeyError: 'Attempt to register an already
    registered destination.'` (from Transport.start re-registering its
    control destinations into the list that survived from the previous
    incarnation). Mirrors the cleanup pattern from columba v0.10.x's
    reticulum_wrapper.shutdown().
    """
    # Reticulum singleton sentinel + one-shot guards.
    setattr(RNS.Reticulum, "_Reticulum__instance", None)
    setattr(RNS.Reticulum, "_Reticulum__exit_handler_ran", False)
    setattr(RNS.Reticulum, "_Reticulum__interface_detach_ran", False)

    # Transport is a static class — all state lives on the class itself.
    # The lists below are everything Transport.start() expects to start
    # empty.
    T = RNS.Transport
    T.owner = None
    T.identity = None
    for attr, default in (
        ("interfaces", list),
        ("destinations", list),
        ("pending_links", list),
        ("active_links", list),
        ("receipts", list),
        ("announce_handlers", list),
        ("discovery_pr_tags", list),
        ("control_destinations", list),
        ("control_hashes", list),
        ("mgmt_destinations", list),
        ("mgmt_hashes", list),
        ("remote_management_allowed", list),
        ("local_client_interfaces", list),
        ("local_client_rssi_cache", list),
        ("local_client_snr_cache", list),
        ("local_client_q_cache", list),
        # The persistent learned-network tables (path_table, announce_table,
        # held_announces, blackholed_identities, tunnels) are reloaded from
        # storage by Transport.start; we just need their in-memory copies
        # cleared so the load happens fresh.
        ("path_table", dict),
        ("announce_table", dict),
        ("held_announces", dict),
        ("blackholed_identities", dict),
        ("tunnels", dict),
        ("packet_hashlist", set),
    ):
        if hasattr(T, attr):
            setattr(T, attr, default())

    # Identity's known-destinations cache is rebuilt from disk on next
    # Identity.load_known_destinations call.
    if hasattr(RNS.Identity, "known_destinations"):
        RNS.Identity.known_destinations = {}


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
