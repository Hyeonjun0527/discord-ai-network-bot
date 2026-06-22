#!/usr/bin/env python3
from __future__ import annotations

import argparse
import re
from collections import Counter, defaultdict
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable

CENTRAL_PREFIX = "com.discordassistant.central"
DEFAULT_SOURCE_ROOT = Path("central-server/src/main/kotlin/com/discordassistant/central")
DEFAULT_OUTPUT = Path("docs/nexa/baseline/central-package-graph.md")
PACKAGE_RE = re.compile(r"^package\s+([A-Za-z0-9_.]+)")
IMPORT_RE = re.compile(r"^import\s+([A-Za-z0-9_.*]+)")
FOCUS_NODES = ("routing", "channelai", "ainetwork")
FOCUS_PREFIXES = ("com.discordassistant.central.platform.discord",)


@dataclass(frozen=True)
class KotlinFile:
    path: Path
    package_name: str
    imports: tuple[str, ...]


@dataclass(frozen=True)
class ImportEdge:
    source_node: str
    target_node: str
    source_package: str
    target_import: str
    path: Path


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--source-root", type=Path, default=DEFAULT_SOURCE_ROOT)
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    parser.add_argument("--check", action="store_true")
    return parser.parse_args()


def read_kotlin_file(path: Path) -> KotlinFile:
    package_name = ""
    imports: list[str] = []
    for line in path.read_text(encoding="utf-8").splitlines():
        package_match = PACKAGE_RE.match(line)
        if package_match:
            package_name = package_match.group(1)
            continue
        import_match = IMPORT_RE.match(line)
        if import_match:
            imports.append(import_match.group(1))
    if not package_name:
        raise ValueError(f"package declaration not found: {path}")
    return KotlinFile(path=path, package_name=package_name, imports=tuple(imports))


def node_for_package(package_name: str) -> str:
    if package_name == CENTRAL_PREFIX:
        return "<root>"
    prefix = f"{CENTRAL_PREFIX}."
    if not package_name.startswith(prefix):
        return "<external>"
    remainder = package_name.removeprefix(prefix)
    return remainder.split(".", 1)[0]


def iter_kotlin_files(source_root: Path) -> list[KotlinFile]:
    return [read_kotlin_file(path) for path in sorted(source_root.rglob("*.kt"))]


def iter_edges(files: Iterable[KotlinFile], repo_root: Path) -> list[ImportEdge]:
    edges: list[ImportEdge] = []
    for kotlin_file in files:
        source_node = node_for_package(kotlin_file.package_name)
        for imported in kotlin_file.imports:
            if not imported.startswith(f"{CENTRAL_PREFIX}."):
                continue
            target_node = node_for_package(imported)
            if source_node == target_node:
                continue
            edges.append(
                ImportEdge(
                    source_node=source_node,
                    target_node=target_node,
                    source_package=kotlin_file.package_name,
                    target_import=imported,
                    path=kotlin_file.path.relative_to(repo_root),
                )
            )
    return edges


def build_adjacency(edges: Iterable[ImportEdge]) -> dict[str, set[str]]:
    adjacency: dict[str, set[str]] = defaultdict(set)
    for edge in edges:
        adjacency[edge.source_node].add(edge.target_node)
        adjacency.setdefault(edge.target_node, set())
    return dict(adjacency)


def find_cycles(adjacency: dict[str, set[str]]) -> list[tuple[str, ...]]:
    index = 0
    stack: list[str] = []
    on_stack: set[str] = set()
    indexes: dict[str, int] = {}
    lowlinks: dict[str, int] = {}
    cycles: list[tuple[str, ...]] = []

    def visit(node: str) -> None:
        nonlocal index
        indexes[node] = index
        lowlinks[node] = index
        index += 1
        stack.append(node)
        on_stack.add(node)

        for target in sorted(adjacency.get(node, set())):
            if target not in indexes:
                visit(target)
                lowlinks[node] = min(lowlinks[node], lowlinks[target])
            elif target in on_stack:
                lowlinks[node] = min(lowlinks[node], indexes[target])

        if lowlinks[node] != indexes[node]:
            return
        component: list[str] = []
        while True:
            current = stack.pop()
            on_stack.remove(current)
            component.append(current)
            if current == node:
                break
        if len(component) > 1:
            cycles.append(tuple(sorted(component)))

    for node in sorted(adjacency):
        if node not in indexes:
            visit(node)
    return sorted(cycles)


def edge_counter(edges: Iterable[ImportEdge]) -> Counter[tuple[str, str]]:
    counter: Counter[tuple[str, str]] = Counter()
    for edge in edges:
        counter[(edge.source_node, edge.target_node)] += 1
    return counter


def sample_edges(edges: Iterable[ImportEdge], source: str, target: str, limit: int = 3) -> list[ImportEdge]:
    matches = [edge for edge in edges if edge.source_node == source and edge.target_node == target]
    return matches[:limit]


def package_file_counts(files: Iterable[KotlinFile]) -> Counter[str]:
    counter: Counter[str] = Counter()
    for kotlin_file in files:
        counter[node_for_package(kotlin_file.package_name)] += 1
    return counter


def outgoing_platform_discord_edges(edges: Iterable[ImportEdge]) -> list[ImportEdge]:
    return [edge for edge in edges if edge.source_package.startswith(FOCUS_PREFIXES)]


def incoming_platform_discord_edges(edges: Iterable[ImportEdge]) -> list[ImportEdge]:
    return [edge for edge in edges if edge.target_import.startswith(FOCUS_PREFIXES)]


def render_edge_table(edges: list[ImportEdge]) -> list[str]:
    counter = edge_counter(edges)
    lines = ["| Source | Target | Imports | Sample path | Sample import |", "| --- | --- | ---: | --- | --- |"]
    for (source, target), count in sorted(counter.items()):
        sample = sample_edges(edges, source, target, 1)[0]
        lines.append(f"| `{source}` | `{target}` | {count} | `{sample.path}` | `{sample.target_import}` |")
    return lines


def render_focus_section(edges: list[ImportEdge], source: str) -> list[str]:
    outgoing = [edge for edge in edges if edge.source_node == source]
    incoming = [edge for edge in edges if edge.target_node == source]
    lines = [f"### `{source}`", "", "Outgoing:", ""]
    lines.extend(render_edge_table(outgoing) if outgoing else ["- none"])
    lines.extend(["", "Incoming:", ""])
    lines.extend(render_edge_table(incoming) if incoming else ["- none"])
    return lines


def render_platform_discord_section(edges: list[ImportEdge]) -> list[str]:
    outgoing = outgoing_platform_discord_edges(edges)
    incoming = incoming_platform_discord_edges(edges)
    lines = ["### `platform/discord`", "", "Outgoing imports from `com.discordassistant.central.platform.discord*`:", ""]
    lines.extend(render_edge_table(outgoing) if outgoing else ["- none"])
    lines.extend(["", "Incoming imports to `com.discordassistant.central.platform.discord*`:", ""])
    lines.extend(render_edge_table(incoming) if incoming else ["- none"])
    return lines


def render_markdown(files: list[KotlinFile], edges: list[ImportEdge]) -> str:
    counts = package_file_counts(files)
    adjacency = build_adjacency(edges)
    cycles = find_cycles(adjacency)
    lines = [
        "# Central package dependency graph baseline",
        "",
        "- Snapshot date: 2026-06-20 KST",
        "- Source root: `central-server/src/main/kotlin/com/discordassistant/central`",
        "- Extraction: Kotlin `package` and `import com.discordassistant.central.*` declarations",
        f"- Compile nodes: {len(counts)} (`<root>` plus top-level central packages)",
        f"- Kotlin files scanned: {len(files)}",
        f"- Cross-node import edges: {len(edges)}",
        "",
        "## Nodes",
        "",
        "| Node | Kotlin files |",
        "| --- | ---: |",
    ]
    for node, count in sorted(counts.items()):
        lines.append(f"| `{node}` | {count} |")

    lines.extend(["", "## Directed cross-node imports", ""])
    lines.extend(render_edge_table(edges))

    lines.extend(["", "## Cycles", ""])
    if cycles:
        lines.append("Top-level compile-node cycles detected:")
        for cycle in cycles:
            lines.append("- " + " ↔ ".join(f"`{node}`" for node in cycle))
    else:
        lines.append("No top-level compile-node cycles detected.")

    lines.extend(["", "## Required focus paths", ""])
    for source in FOCUS_NODES:
        lines.extend(render_focus_section(edges, source))
        lines.append("")
    lines.extend(render_platform_discord_section(edges))

    lines.extend(
        [
            "",
            "## Reproduce",
            "",
            "```bash",
            "python3 scripts/central-package-graph.py --check",
            "```",
            "",
            "`--check` regenerates this markdown in memory and fails if the committed snapshot drifts.",
        ]
    )
    return "\n".join(lines) + "\n"


def main() -> int:
    args = parse_args()
    repo_root = Path.cwd().resolve()
    source_root = args.source_root.resolve()
    if not source_root.exists():
        raise SystemExit(f"source root not found: {source_root}")
    files = iter_kotlin_files(source_root)
    edges = iter_edges(files, repo_root)
    content = render_markdown(files, edges)
    if args.check:
        expected = args.output.read_text(encoding="utf-8")
        if expected != content:
            print(f"package graph snapshot drift: {args.output}")
            return 1
        print(f"package graph snapshot OK: {args.output}")
        return 0
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(content, encoding="utf-8")
    print(f"wrote {args.output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
