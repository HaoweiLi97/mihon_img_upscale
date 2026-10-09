"""Compare host shared libraries with the extension compiler's version catalog.

Requires Python 3.11+. This checks declared versions, not every binary symbol or
the final Gradle dependency graph. Check releaseRuntimeClasspath and the minified
DEX as well when changing versions.
"""

import argparse
import pathlib
import re
import sys
import tomllib
import urllib.request


ROOT = pathlib.Path(__file__).resolve().parents[1]
UPSTREAM = "https://raw.githubusercontent.com/keiyoushi/extensions-source/main/gradle/libs.versions.toml"


def modules(catalog):
    result = {}
    for library in catalog["libraries"].values():
        if isinstance(library, str):
            group, name, version = library.split(":", 2)
            module = f"{group}:{name}"
        else:
            module = library.get("module") or f"{library['group']}:{library['name']}"
            version = library.get("version")
            if isinstance(version, dict):
                version = catalog["versions"][version["ref"]]
        result[module] = version
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--catalog", type=pathlib.Path, help="Use a saved upstream catalog instead of the network")
    args = parser.parse_args()
    if args.catalog:
        upstream = tomllib.loads(args.catalog.read_text(encoding="utf-8"))
    else:
        with urllib.request.urlopen(UPSTREAM, timeout=30) as response:
            upstream = tomllib.loads(response.read().decode())
    host = {}
    for path in (ROOT / "gradle").glob("*.versions.toml"):
        host.update(modules(tomllib.loads(path.read_text(encoding="utf-8"))))
    coroutines = host["org.jetbrains.kotlinx:kotlinx-coroutines-bom"]
    for module in host:
        if module.startswith("org.jetbrains.kotlinx:kotlinx-coroutines-") and host[module] is None:
            host[module] = coroutines
    host["org.jetbrains.kotlin:kotlin-stdlib-jdk8"] = host["org.jetbrains.kotlin:kotlin-gradle-plugin"]

    failures = 0
    upstream_modules = modules(upstream)
    for alias in upstream["bundles"]["common"]:
        library = upstream["libraries"][alias]
        module = library["module"] if isinstance(library, dict) else ":".join(library.split(":")[:2])
        required = upstream_modules[module]
        provided = host.get(module)
        if provided is None:
            # Some modules arrive transitively or through a compatible fork.
            print(f"REVIEW {module}: upstream {required}; check the resolved runtime classpath")
            continue
        if provided == required:
            status = "OK"
        elif re.fullmatch(r"\d+(?:\.\d+)+", provided) and re.fullmatch(r"\d+(?:\.\d+)+", required):
            status = "OK" if tuple(map(int, provided.split("."))) >= tuple(map(int, required.split("."))) else "OLD"
        else:
            status = "REVIEW"
        failures += status == "OLD"
        print(f"{status} {module}: host {provided}; upstream {required}")
    return int(failures > 0)


if __name__ == "__main__":
    sys.exit(main())
