#!/usr/bin/env python3
# SPDX-License-Identifier: MPL-2.0
"""
Regression test for event_bridge's restart-compat reset contract.

event_bridge.reticulum_reset_class_state() clears RNS's class-level state so
the watchdog can drive Reticulum through repeated start → shutdown → restart
cycles. The reset's DEFAULT TYPES are load-bearing, not cosmetic: RNS 1.5.x
flipped Transport.discovery_pr_tags (and added discovery_pr_tags_prev) from
lists to sets — Transport.inbound() calls .add() on them, so a stale list
left by the previous incarnation would AttributeError on the first
post-restart inbound packet. (RNS 1.2.5, which this was written against,
declared it `[]`.)

This test runs against the REAL pinned RNS wheel (installed by CI from the
same pin as eridanus-rns-backend-py/build.gradle.kts) and asserts, for every
entry in TRANSPORT_CLASS_STATE:
  1. the attribute exists on the real Transport class, and
  2. its live declared type matches the reset's factory type — so a future
     RNS bump that changes a type (e.g. set → list) fails CI here instead of
     shipping an AttributeError into the inbound path.

It then simulates the restart path (shutdown teardown + re-reset, the exact
sequence PyReticulumService drives) and asserts the post-reset collections
support the operations RNS 1.5.x performs on them (.add on the discovery-tag
sets, extend on the list tables) — i.e. the failure the 1.5.2 bump
introduced can no longer silently come back.

Usage:
    pip install "rns==<pin>" && python scripts/test_event_bridge_reset_contract.py

Exit codes:
    0 - contract holds against the installed RNS
    1 - mismatch (type drift or reset leaves a wrong-typed collection)
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
# RNS hit the cache. RNS only imports cryptography lazily (crypto provider
# selection), and the reset contract never exercises that path. No-op in CI,
# where a single `pip install rns==<pin>` puts one linux wheel in one
# site-packages.
try:
    import cryptography  # noqa: F401
except ImportError:
    pass

# Prepend the RNS package from the pinned wheel.
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

    T = RNS.Transport

    # 1. Every reset target exists on the real Transport class and its
    #    declared live type matches the reset's factory type.
    for attr, default in event_bridge.TRANSPORT_CLASS_STATE:
        if not hasattr(T, attr):
            fail(f"Transport.{attr} no longer exists — remove from "
                 f"TRANSPORT_CLASS_STATE or RNS changed its surface")
        live = type(getattr(T, attr))
        if live is not default:
            fail(f"RNS {RNS.__version__} declares Transport.{attr} as "
                 f"{live.__name__}, but the reset leaves {default.__name__} — "
                 f"update TRANSPORT_CLASS_STATE (this is the RNS-1.5.x "
                 f"list→set class of bug)")

    # 2. Simulate the restart path: leave stale per-type values behind
    #    (as a previous incarnation would), run the exact teardown + reset
    #    sequence the watchdog drives, then prove the post-reset state
    #    supports what RNS 1.5.x actually does with it.
    stale = {list: ["stale"], set: {"stale"}, dict: {"stale": 1}}
    for attr, default in event_bridge.TRANSPORT_CLASS_STATE:
        setattr(T, attr, stale[default])
    event_bridge.reticulum_shutdown(None)  # exercises the no-op guard path
    event_bridge.reticulum_reset_class_state()

    T.discovery_pr_tags.add(b"post-restart-tag")
    T.discovery_pr_tags_prev.add(b"post-restart-prev-tag")
    T.interfaces.extend([])
    T.destinations.extend([])
    T.packet_hashlist.add(b"pkt")
    if T.owner is not None or T.identity is not None:
        fail("reset left Transport.owner/identity non-None")
    if RNS.Identity.known_destinations != {}:
        fail("reset left Identity.known_destinations non-empty")

    print(f"OK: reset contract holds against RNS {RNS.__version__} "
          f"({len(event_bridge.TRANSPORT_CLASS_STATE)} attrs verified)")


if __name__ == "__main__":
    main()
