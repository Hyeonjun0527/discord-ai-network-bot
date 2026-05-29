# Changelog

## [Unreleased]

### Added
- Multi-provider LLM support (Ollama, OpenAI, Anthropic)
- Interactive `/settings` panel with Discord UI components (buttons, modals, select menus)
- `/chat` command for context-free AI conversation
- `/help` command with embedded command reference
- `/ask` command for context-aware Q&A using recent channel messages
- `/translate` command for text translation
- `/summarize` command with in-memory 60-second TTL cache
- Encrypted API key storage (Fernet symmetric encryption)
- Ollama model install with progress tracking via `OllamaManager`
- Ollama `keep_alive` setting to keep model loaded in memory
- Automatic retry logic (2 attempts) for transient LLM errors
- Slow response warning log when latency exceeds 30 seconds
- Background memory usage monitoring (psutil, hourly)
- Cache invalidation on new channel messages
- Long message truncation (>500 chars) in transcript builder
- Near-duplicate message filtering in transcript builder
- `aiosqlite` dependency for async-ready SQLite access
- GitHub Actions CI with ruff lint, mypy type checks, and pytest
- pytest coverage reporting via pytest-cov
- `tests/conftest.py` with reusable async `store` fixture
- `tests/load_test.py` for concurrent Ollama load testing
- `docs/quality_evaluation.md` with model quality scoring rubric
- `pyproject.toml` ruff lint rules and mypy configuration

### Changed
- `OllamaClient._generate_sync` now includes `keep_alive` in the request body
- All LLM `generate` methods wrapped with `_with_retry` for resilience
- CI workflow runs ruff and mypy before pytest

### Fixed
- Potential log exposure of sensitive tokens (confirmed absent in codebase)
- All SQL queries verified to use parameterized `?` bindings (no f-string injection)
