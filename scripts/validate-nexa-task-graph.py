#!/usr/bin/env python3
from __future__ import annotations

import runpy
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[1]
VALIDATOR = REPO_ROOT / "docs" / "nexa" / "validate_nexa_500_task_graph.py"
DEFAULT_GRAPH = REPO_ROOT / "docs" / "nexa" / "nexa_500_task_graph.yaml"


def main() -> None:
    if len(sys.argv) == 1:
        sys.argv.append(str(DEFAULT_GRAPH))
    runpy.run_path(str(VALIDATOR), run_name="__main__")


if __name__ == "__main__":
    main()
