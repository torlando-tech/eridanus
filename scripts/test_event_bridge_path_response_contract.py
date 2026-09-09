#!/usr/bin/env python3
# SPDX-License-Identifier: MPL-2.0
"""
Regression test for event_bridge's announce-handler path-response contract.

RRC discovers a hub whose hash the user entered manually ONLY as the reply to
its own `request_path()` — RNS tags that reply with `Packet.PATH_RESPONSE`.
Both backends (python RNS Transport.inbound, and reticulum-kt's mirror of it)
gate PATH_RESPONSE delivery behind an explicit opt-in:

    python  (Transport.py, RNS 1.5.x):
        if packet.context == RNS.Packet.PATH_RESPONSE:
            if hasattr(handler, "receive_path_responses") and \\
               handler.receive_path_responses == True: pass
            else: execute_callback = False

    reticulum-kt (Transport.kt):
        if (isPathResponse) {
            val wants = (handler as? RichAnnounceHandler)?.receivePathResponses == true
            if (!wants) continue
        }

A handler that has NOT opted in still receives normal periodic announces, but
silently drops every path response. That is exactly the "app forgets the hub
after a restart" bug in issue #42: the manual-hash path-response announce is
the ONLY announce a not-yet-discovered hub produces, so with the opt-in
missing it never reached the discovered-hub table.

This test runs against the REAL pinned RNS wheel (CI installs the same pin as
eridanus-rns-backend-py/build.gradle.kts) and asserts:
  1. `RNS.Packet.PATH_RESPONSE` exists (the context the gate compares on), and
  2. `event_bridge.announce_handler(..., receive_path_responses=True)` produces
     a handler whose `receive_path_responses` attribute is `True` — i.e. it
     satisfies RNS's own gate expression — while the default (opt-out)
     produces a handler that does NOT.

If a future change renames the attribute, drops the assignment, or flips the
default, the gate expression below stops matching and this test fails — the
"manually-entered hub never appears" regression can no longer ship silently.

Usage:
    pip install "rns==<pin>" && python scripts/test_event_bridge_path_response_contract.py

Exit codes:
    0 - contract holds against the installed RNS
    1 - mismatch (attribute missing / wrong value / gate expression not met)
    2 - usage error (RNS not installed / module not found)
"""
import sys
from pathlib import Path
from typing import NoReturn

REPO = Path(__file__).resolve().parent.parent
# Load the HOST cryptography before the path insertions below: the chaquopy
# build cache (eridanus-rns-backend-py/build/python/pip/debug/common) also
# contains a `cryptography` — the ANDROID wheel, whose Rust bindings are .so
# files that only load on device. sys.modules wins over sys.path, so
# pre-importing the host one makes every later `from cryptography...` inside
# RNS hit the cache. No-op in CI, where a single `pip install rns==<pin>`
# puts one linux wheel in one site-packages.
try:
    import cryptography  # noqa: F401
except ImportError:
    pass

# Prepend the event_bridge module + the pinned RNS wheel.
sys.path.insert(0, str(REPO / "eridanus-rns-backend-py" / "src" / "main" / "python"))
sys.path.insert(0, str(REPO / "eridanus-rns-backend-py" / "build" / "python" / "pip" / "debug" / "common"))


def fail(msg: str) -> NoReturn:
    print(f"FAIL: {msg}", file=sys.stderr)
    sys.exit(1)


def main() -> None:
    try:
        import RNS  # noqa: F401  (must be the pinned wheel, not something stale)
        import event_bridge
    except ImportError as e:
        fail(f"cannot import (is the pinned rns wheel installed?): {e}")

    # 1. The context RNS tags a path-response announce with must exist.
    if not hasattr(RNS.Packet, "PATH_RESPONSE"):
        fail(f"RNS {RNS.__version__} no longer has Packet.PATH_RESPONSE — "
             f"the path-response gate keyed on it has changed; re-derive "
             f"event_bridge.announce_handler's opt-in against the new API")

    # A throwaway callback: event_bridge never invokes it here, it only needs
    # the correct arity (Function3) for the wrapper to construct.
    class _KtCb:
        def call(self, *args):
            return False

    def rns_gate_admits(handler) -> bool:
        """The EXACT boolean RNS's dispatch loop evaluates before delivering
        a PATH_RESPONSE to a handler (Transport.py). True = the handler will
        receive path responses."""
        return hasattr(handler, "receive_path_responses") and handler.receive_path_responses == True  # noqa: E712

    # 2. Opted-in handler must satisfy RNS's own gate.
    opted_in = event_bridge.announce_handler(_KtCb(), aspect_filter="rrc.hub", receive_path_responses=True)
    if not hasattr(opted_in, "receive_path_responses"):
        fail("opted-in handler is missing the `receive_path_responses` attribute — "
             "RNS will drop every path response (issue #42 regression)")
    if opted_in.receive_path_responses is not True:
        fail(f"opted-in handler.receive_path_responses is {opted_in.receive_path_responses!r}, "
             f"expected True — the gate requires strict `== True`")
    if not rns_gate_admits(opted_in):
        fail("opted-in handler does not satisfy RNS's path-response gate expression — "
             "RNS will still drop path responses")

    # 3. The aspect filter must be wired through unchanged (regression guard
    #    against the opt-in change breaking the existing aspect scoping).
    if opted_in.aspect_filter != "rrc.hub":
        fail(f"opted-in handler.aspect_filter is {opted_in.aspect_filter!r}, expected 'rrc.hub'")

    # 4. Default (opt-out) handler must NOT admit path responses — the
    #    historical behavior for handlers that haven't asked for them.
    opted_out = event_bridge.announce_handler(_KtCb(), aspect_filter="rrc.hub")
    if rns_gate_admits(opted_out):
        fail("default (opt-out) handler satisfies the path-response gate — "
             "the opt-in flag is no longer opt-in; non-RRC handlers would start "
             "receiving path responses they never asked for")

    print(f"OK: path-response contract holds against RNS {RNS.__version__} "
          f"(opt-in admits, default drops, aspect filter intact)")


if __name__ == "__main__":
    main()
