#!/usr/bin/env python3
"""Print release notes grouped per game from commits since the previous v* tag.

Usage: release_notes.py <tag>
"""
import subprocess
import sys

GROUPS = [
    ("Minecraft", ("plugins/minecraft/",)),
    ("Rust", ("plugins/rust/",)),
    ("Hytale", ("plugins/hytale/",)),
    ("FiveM / RedM", ("plugins/fivem/",)),
    ("Conformance", ("conformance/",)),
    ("Repository", (".github/", "README.md", "NOTES.md", "VERSION", "docs/")),
]


def git(*args: str) -> str:
    return subprocess.run(["git", *args], capture_output=True, text=True, check=False).stdout.strip()


def main() -> None:
    tag = sys.argv[1]
    prev = git("describe", "--tags", "--abbrev=0", "--match", "v*", f"{tag}^") if git("rev-parse", "-q", "--verify", tag) else ""
    if not prev:
        prev = git("describe", "--tags", "--abbrev=0", "--match", "v*", "HEAD^")
    rng = f"{prev}..HEAD" if prev else "HEAD"
    print(f"# Player Manager Bridge {tag}\n")
    print("Install through the panel (it reads `manifest.json`), or download the asset for your platform. "
          "Verify downloads with `SHA256SUMS`. Deviations from the panel contract are listed in `NOTES.md`.\n")
    for title, paths in GROUPS:
        log = git("log", "--no-merges", "--format=- %s (%h)", rng, "--", *paths)
        if log:
            print(f"## {title}\n\n{log}\n")


if __name__ == "__main__":
    main()
