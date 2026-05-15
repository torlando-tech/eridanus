#!/usr/bin/env python3
"""
Verify the python-flavor's kotlin↔python bridge survives R8 minification.

event_bridge.py calls back into this app's Kotlin classes by reflective
name via chaquopy — `kt_cb.call(...)` on the Py*Callback classes for
announce / link / packet / resource callbacks. If R8 renames either a
callback class or its `call` method, every callback throws at runtime:

    AttributeError: '<minified>' object has no attribute 'call'

and e.g. received announces silently never reach the app. Debug builds
aren't minified, so this only bites release builds — which makes it an
easy regression to ship unnoticed. Hence this CI gate.

The callback class list is discovered dynamically from PyRnsCallbacks.kt,
so a new Py*Callback is covered automatically. For each, this asserts
against the R8 mapping that the class was NOT renamed and that it still
has a method named `call`.

Usage:
    python scripts/verify_python_bridge.py <path-to-pythonRelease-mapping.txt>

Exit codes:
    0 - bridge intact
    1 - a callback class or its `call` method was renamed/stripped
    2 - usage error / file not found
"""
import re
import sys
from pathlib import Path

PKG = "tech.torlando.eridanus.rns.py"


def discover_callback_classes(callbacks_kt: Path) -> set[str]:
    """Pull the Py*Callback class names straight from PyRnsCallbacks.kt so
    the keep-list can't drift from the source."""
    text = callbacks_kt.read_text(encoding="utf-8")
    return set(re.findall(r"\bclass\s+(Py\w*Callback)\b", text))


def parse_mapping(mapping_txt: Path) -> dict[str, dict]:
    """Parse R8 mapping.txt into {original_class: {"renamed": str,
    "methods": set_of_original_method_names_kept_unrenamed}}.

    A class line:   `orig.Class -> renamed:`
    A member line:  `    [range:]ret orig.method(args)[:lines] -> renamed`
    Method name is kept iff its original name == renamed name.
    """
    classes: dict[str, dict] = {}
    current: str | None = None
    for raw in mapping_txt.read_text(encoding="utf-8").splitlines():
        if not raw or raw.lstrip().startswith("#"):
            continue
        if not raw.startswith(" ") and raw.rstrip().endswith(":") and " -> " in raw:
            orig, renamed = raw.rstrip()[:-1].split(" -> ", 1)
            current = orig.strip()
            classes[current] = {"renamed": renamed.strip(), "methods": set()}
        elif raw.startswith(" ") and current is not None and " -> " in raw:
            left, renamed = raw.strip().rsplit(" -> ", 1)
            m = re.search(r"(\w+)\([^)]*\)", left)  # method sig: name(args)
            if m and m.group(1) == renamed.strip():
                classes[current]["methods"].add(m.group(1))
    return classes


def main() -> None:
    if len(sys.argv) != 2:
        print(f"Usage: {sys.argv[0]} <path-to-pythonRelease-mapping.txt>")
        sys.exit(2)

    mapping_txt = Path(sys.argv[1])
    if not mapping_txt.exists():
        print(f"ERROR: mapping.txt not found: {mapping_txt}")
        sys.exit(2)

    project_dir = Path(__file__).parent.parent.resolve()
    callbacks_kt = (
        project_dir
        / "eridanus-rns-backend-py/src/main/kotlin"
        / PKG.replace(".", "/")
        / "PyRnsCallbacks.kt"
    )
    if not callbacks_kt.exists():
        print(f"ERROR: PyRnsCallbacks.kt not found: {callbacks_kt}")
        sys.exit(2)

    print("=" * 60)
    print("Python-Kotlin Bridge R8 Verification (python flavor)")
    print("=" * 60)
    print()

    callbacks = discover_callback_classes(callbacks_kt)
    if not callbacks:
        print(f"ERROR: no Py*Callback classes found in {callbacks_kt.name}")
        sys.exit(2)
    print(f"Discovered {len(callbacks)} bridge callback class(es): "
          f"{', '.join(sorted(callbacks))}")
    print(f"Parsing R8 mapping: {mapping_txt}")
    classes = parse_mapping(mapping_txt)
    print()

    failures: list[str] = []
    for name in sorted(callbacks):
        fq = f"{PKG}.{name}"
        entry = classes.get(fq)
        if entry is None:
            failures.append(f"{name}: absent from mapping (stripped by R8?)")
            print(f"  X {name}: NOT in mapping")
            continue
        if entry["renamed"] != fq:
            failures.append(f"{name}: class renamed to '{entry['renamed']}'")
            print(f"  X {name}: class renamed -> {entry['renamed']}")
            continue
        if "call" not in entry["methods"]:
            failures.append(f"{name}: `call` method renamed or stripped")
            print(f"  X {name}: class kept but `call` method NOT preserved")
            continue
        print(f"  + {name}: class + `call` method preserved")

    print()
    print("=" * 60)
    if failures:
        print("VERIFICATION FAILED")
        print("=" * 60)
        print()
        print("The python flavor's kotlin->python callback bridge is broken")
        print("by R8. event_bridge.py's `kt_cb.call(...)` will throw")
        print("AttributeError at runtime on every affected callback.")
        print()
        for f in failures:
            print(f"  - {f}")
        print()
        print("Fix: the Py*Callback types must be concrete classes (not")
        print("`fun interface`s — SAM lambdas become R8 synthetics that")
        print("name-pattern keep rules don't pin), and")
        print("eridanus-rns-backend-py/consumer-rules.pro must keep")
        print(f"`{PKG}.**`.")
        sys.exit(1)

    print("VERIFICATION PASSED")
    print("=" * 60)
    print()
    print("All python-callable bridge classes and their `call` methods")
    print("survived R8 minification.")
    sys.exit(0)


if __name__ == "__main__":
    main()
