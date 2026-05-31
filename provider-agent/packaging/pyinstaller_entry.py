"""PyInstaller entrypoint for the provider agent executable.

PyInstaller executes the script passed to ``Analysis`` as a top-level module.
Pointing it directly at ``provider_agent/__main__.py`` therefore makes
``__package__`` empty and breaks that module's relative imports.  Keep this
launcher outside the package and import the real console entrypoint by its
absolute package name instead.
"""
from __future__ import annotations

from provider_agent.__main__ import main


if __name__ == "__main__":  # pragma: no cover
    raise SystemExit(main())
