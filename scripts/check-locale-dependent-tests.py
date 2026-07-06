#!/usr/bin/env python3
"""Fail tests that assert locale-sensitive response text as a single-language string."""

from __future__ import annotations

import ast
import re
import sys
from dataclasses import dataclass
from pathlib import Path

DEFAULT_PATHS = (Path("provider-agent/tests"),)
LOCALE_TEXT_RE = re.compile(r"[가-힣]")
ASCII_FALLBACK_RE = re.compile(r"['\"][A-Za-z][^'\"]*['\"]")
I18N_TEST_NAME_RE = re.compile(r"i18n", re.IGNORECASE)
RESPONSE_TEXT_FIELDS = frozenset({"error", "message", "reason", "detail", "failReason"})


@dataclass(frozen=True)
class Finding:
    path: Path
    line: int
    source: str


def _has_korean_string(node: ast.AST) -> bool:
    for child in ast.walk(node):
        if isinstance(child, ast.Constant) and isinstance(child.value, str):
            if LOCALE_TEXT_RE.search(child.value):
                return True
    return False


def _has_response_text_subscript(node: ast.AST) -> bool:
    for child in ast.walk(node):
        if not isinstance(child, ast.Subscript):
            continue
        index = child.slice
        if isinstance(index, ast.Constant) and index.value in RESPONSE_TEXT_FIELDS:
            return True
    return False


def _has_locale_fallback(source: str) -> bool:
    return " or " in source and ASCII_FALLBACK_RE.search(source) is not None


def _is_candidate_assert(source: str, node: ast.Assert) -> bool:
    if not _has_korean_string(node):
        return False
    if not _has_response_text_subscript(node):
        return False
    return not _has_locale_fallback(source)


def _python_files(paths: list[Path]) -> list[Path]:
    files: list[Path] = []
    for path in paths:
        if path.is_file() and path.suffix == ".py":
            files.append(path)
        elif path.is_dir():
            files.extend(sorted(path.rglob("*.py")))
    return files


def _scan_file(path: Path) -> list[Finding]:
    if I18N_TEST_NAME_RE.search(path.name):
        return []

    text = path.read_text(encoding="utf-8")
    tree = ast.parse(text, filename=str(path))
    findings: list[Finding] = []
    for node in ast.walk(tree):
        if not isinstance(node, ast.Assert):
            continue
        source = ast.get_source_segment(text, node) or ""
        one_line = " ".join(source.split())
        if _is_candidate_assert(one_line, node):
            findings.append(Finding(path=path, line=node.lineno, source=one_line))
    return findings


def main(argv: list[str]) -> int:
    paths = [Path(arg) for arg in argv] if argv else list(DEFAULT_PATHS)
    findings: list[Finding] = []
    for path in _python_files(paths):
        findings.extend(_scan_file(path))

    if not findings:
        return 0

    print("Locale-dependent response text assertions found:", file=sys.stderr)
    for finding in findings:
        print(f"{finding.path}:{finding.line}: {finding.source}", file=sys.stderr)
    print(
        "Assert stable fields/codes, or include explicit locale fallbacks in the same assertion.",
        file=sys.stderr,
    )
    return 1


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
