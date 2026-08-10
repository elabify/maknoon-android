#!/usr/bin/env bash
#
# Per-surface i18n check for the public app mirrors. One definition for both,
# like leak-scan.sh; it detects which app it is standing in.
#
# The full gate cross-checks every surface against the translation memory
# upstream. This repo holds ONE app and no `tm/`, so that gate cannot run here.
# What it CAN prove is that the surface it does hold is internally coherent:
# that every locale the app declares is a locale it actually ships, and the
# reverse.
#
# That is worth checking separately rather than trusting upstream, because
# these two sets are maintained in different files and drifted before:
# registration (knownRegions / CFBundleLocalizations / locales_config.xml) and
# coverage (.lproj / values-*) are different things, and conflating them made
# Stage 2 un-shippable until the gate was fixed.
#
#   bash .github/i18n-surface.sh

set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
status=0

fail() { echo "FAIL: $*" >&2; status=1; }

# ---------------------------------------------------------------------------
# iOS: the String Catalog is the coverage, project.yml is the registration.
# ---------------------------------------------------------------------------
CATALOG="$ROOT/ios-app-maknoon/Maknoon/Resources/Localizable.xcstrings"
PROJECT="$ROOT/ios-app-maknoon/project.yml"

if [ -f "$CATALOG" ]; then
  echo "== iOS locale registration vs catalog coverage"
  python3 - "$CATALOG" "$PROJECT" <<'PY' || status=1
import json, re, sys

catalog, project = sys.argv[1], sys.argv[2]
doc = json.loads(open(catalog, encoding="utf-8").read())

# A key with no localizations at all is source-only, which is normal. The
# shipped set is every locale that appears anywhere in the catalog.
shipped = set()
for entry in doc.get("strings", {}).values():
    shipped |= set(entry.get("localizations", {}))
shipped.add(doc.get("sourceLanguage", "en"))

text = open(project, encoding="utf-8").read()

def block(name):
    m = re.search(rf"{name}:\s*\n((?:\s*-\s*\S+\n)+)", text)
    found = set(re.findall(r"-\s*(\S+)", m.group(1))) if m else set()
    # `Base` is Xcode's pseudo-locale for the base localization, not a language,
    # and never appears in the catalog.
    return found - {"Base"}

known = block("knownRegions")
bundle = block("CFBundleLocalizations")

bad = False
if known and known != shipped:
    print(f"  knownRegions vs catalog: only in project.yml {sorted(known - shipped)}, "
          f"only in catalog {sorted(shipped - known)}")
    bad = True
if bundle and bundle != shipped:
    print(f"  CFBundleLocalizations vs catalog: only in project.yml "
          f"{sorted(bundle - shipped)}, only in catalog {sorted(shipped - bundle)}")
    bad = True
if bad:
    raise SystemExit(1)
print(f"  ok: {len(shipped)} locales, registration and coverage agree")
PY
fi

# ---------------------------------------------------------------------------
# Android: values-* is the coverage, locales_config.xml is the registration.
# ---------------------------------------------------------------------------
RES="$ROOT/android-app-elabify/app/src/main/res"

if [ -d "$RES" ]; then
  echo "== Android locale registration vs resource coverage"
  python3 - "$RES" <<'PY' || status=1
import re, sys, collections
from pathlib import Path

res = Path(sys.argv[1])

def to_bcp47(dirname: str) -> str:
    """values-b+zh+Hans -> zh-Hans, values-ar -> ar."""
    tag = dirname[len("values-"):]
    return tag[2:].replace("+", "-") if tag.startswith("b+") else tag

shipped = {to_bcp47(p.name) for p in res.glob("values-*")
           if (p / "strings.xml").exists()}
shipped.add("en")

cfg = res / "xml" / "locales_config.xml"
if not cfg.exists():
    print("  locales_config.xml is missing; Android would offer no per-app language")
    raise SystemExit(1)
declared = set(re.findall(r'android:name="([^"]+)"', cfg.read_text(encoding="utf-8")))

if declared != shipped:
    print(f"  locales_config vs values-*: only declared {sorted(declared - shipped)}, "
          f"only shipped {sorted(shipped - declared)}")
    raise SystemExit(1)

# A duplicate <string name> is silently won by the later entry, so the earlier
# copy simply stops rendering. Gradle catches it at merge time; catch it here,
# before a build, and in every locale rather than just the base.
dupes = 0
for path in [res / "values" / "strings.xml", *res.glob("values-*/strings.xml")]:
    names = re.findall(r'<string(?:-array)?\s+name="([^"]+)"', path.read_text(encoding="utf-8"))
    for name, n in collections.Counter(names).items():
        if n > 1:
            print(f"  {path.parent.name}/strings.xml defines {name!r} {n} times")
            dupes += 1
if dupes:
    raise SystemExit(1)
print(f"  ok: {len(shipped)} locales, registration and coverage agree, no duplicate keys")
PY
fi

if [ "$status" -ne 0 ]; then
  echo >&2
  echo "FAIL: the per-surface i18n check is red." >&2
  echo "  This mirror is generated. Fix it in the musnad monorepo and re-publish;" >&2
  echo "  do not patch the mirror directly." >&2
  exit 1
fi
echo "ok: per-surface i18n check passed"
