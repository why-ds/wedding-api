#!/usr/bin/env python3
"""Prune only direct, SHA-named directories; protect the active and rollback releases."""
import argparse
import pathlib
import re
import shutil


def prune(root: pathlib.Path, protected: set[str], keep: int = 5) -> None:
    if root.is_symlink() or not root.is_dir():
        raise ValueError("Release root must be an existing directory, not a symlink")
    root = root.resolve(strict=True)
    releases = sorted(
        (p for p in root.iterdir() if re.fullmatch(r"[0-9a-f]{40}", p.name)
         and not p.is_symlink() and p.is_dir()),
        key=lambda p: (p.stat().st_mtime_ns, p.name), reverse=True,
    )
    retained = protected | {p.name for p in releases[:keep]}
    for path in releases:
        if path.name in retained:
            continue
        if path.is_symlink() or path.resolve(strict=True) != root / path.name:
            raise ValueError("Unsafe release path")
        shutil.rmtree(path)


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("root", type=pathlib.Path)
    parser.add_argument("--protect", action="append", default=[])
    args = parser.parse_args()
    prune(args.root, set(args.protect))
