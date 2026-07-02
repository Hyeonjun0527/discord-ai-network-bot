# CI/CD workflow baseline

- Snapshot date: 2026-06-20 KST
- Branch: `feat/nexa-p00-t001-baseline`
- Source directory: `.github/workflows/`
- Workflow files found: **10**
- Method: parsed current workflow YAML with `yaml.BaseLoader`, then verified path/job/secret/cache surfaces with grep.

This baseline separates the workflow files NEXA work may need to touch from release/deploy/ops workflows that must not be edited unless a task explicitly asks for that surface.

## Workflow matrix

| Workflow file | Name | Trigger | Jobs / runners | Main build or action target | Secrets / elevated permissions | Cache / artifact surface | NEXA edit lane |
| --- | --- | --- | --- | --- | --- | --- | --- |
| `.github/workflows/central-server-ci.yml` | central-server CI | `push`, `pull_request`, `workflow_dispatch`; path-filtered to `central-server/**`, `scripts/cleanup-testcontainers.sh`, and this workflow | `build`, `integration` on `[self-hosted, ARM64]` | `./gradlew build`; `./gradlew test -PdockerTests`; Testcontainers label-scoped cleanup on `always()` | no explicit secrets; default token only | uploads test reports; no explicit cache action; prunes only Docker resources with `org.testcontainers=true` | **Allowed/minimal for NEXA central Kotlin/test CI only** |
| `.github/workflows/provider-agent-ci.yml` | provider-agent CI | `push`, `pull_request`, `workflow_dispatch`; path-filtered to `provider-agent/**`, `packaging/**`, `i18n/**`, `docs/**`, `specs/**`, selected scripts, and this workflow | `agent`, `docs-links` on `[self-hosted, ARM64]` | provider pytest/ruff/mypy; docs links; packaging/i18n checks | no explicit secrets | no explicit cache action | **Allowed only for provider-agent/docs check coverage; do not add central deploy behavior here** |
| `.github/workflows/agent-autorelease.yml` | agent-autorelease | `push` to `main` on `provider-agent/src/**` or `provider-agent/pyproject.toml`; `workflow_dispatch` | `tag` on `[self-hosted, yeon-arm]` | computes next `agent-v*`, edits version files, pushes tag, dispatches agent build | `contents: write`, `actions: write` | no explicit cache | **Do not touch for NEXA social behavior work** |
| `.github/workflows/agent-build.yml` | agent-build | `workflow_dispatch`; `push` tags `agent-v*` | model URL check, matrix binary build, release, package-manager publication, central download placement | PyInstaller binaries, signing/notarization, SBOM/provenance, GitHub Release, Homebrew/Scoop/winget PRs | many signing/package secrets: `MACOS_*`, `APPLE_*`, `WINDOWS_*`, `ES_*`, `PKG_APP_*`, `WINGET_GH_TOKEN`; write/id-token/attestations permissions | uploads release/package artifacts; no explicit cache action | **Do not touch unless task is agent release/packaging** |
| `.github/workflows/ai-rag-rebuild.yml` | AI RAG 인덱스 재빌드 | `push` to `main` on `rag/**`, RAG docker/compose/script, this workflow, and `docs/**/*.md`; `workflow_dispatch` | `rebuild` on `[self-hosted, yeon-arm]` | Qdrant helper down, `scripts/rag.sh build-local`, `scripts/rag.sh eval`, commits generated metadata | `ENV_FILE`; `contents: write` | Docker compose/Qdrant local state, generated metadata commit | **Do not touch for NEXA unless RAG/docs trigger policy is the explicit task** |
| `.github/workflows/central-deploy.yml` | central-server CI/CD (원격 배포) | `push` to `main` on `central-server/**` or this workflow; `workflow_dispatch` | `build` and `deploy` on `[self-hosted, yeon-arm]` | central bootJar, GHCR image push, remote compose pull/up/health on port 8085 | `ENV_FILE`, `GITHUB_TOKEN`; `contents: read`, `packages: write` | Docker build-push; GHCR image; self-hosted deploy dir | **Do not touch without deploy task / production approval** |
| `.github/workflows/central-release.yml` | central-server release | `push` tags `central-v*`; `workflow_dispatch` with tag input | `release` on `[self-hosted, yeon-arm]` | validates tag, extracts `central-server/CHANGELOG.md`, creates GitHub Release | `contents: write`; `gh release create` | release notes artifact implicit; no explicit cache | **Do not touch unless central release process changes** |
| `.github/workflows/central-server-deploy.yml` | central-server deploy (self-hosted, deprecated) | `workflow_dispatch` only | `deploy` on `[self-hosted]` | legacy clean bootJar + compose up + localhost health + rollback note | `ENV_FILE`; default token | legacy self-hosted compose state; no explicit cache | **Do not touch; deprecated manual deploy path** |
| `.github/workflows/central-server-image.yml` | central-server image | `push` tags `central-v*`; `workflow_dispatch` | `build-push` on `[self-hosted, yeon-arm]` | central clean bootJar, Docker build-push to GHCR | `GITHUB_TOKEN`; job permissions `contents: read`, `packages: write` | Docker build-push; GHCR tags `sha-*`, `central-v*`, `latest` | **Do not touch unless central image release changes** |
| `.github/workflows/ghcr-cleanup.yml` | GHCR Cleanup | weekly schedule, `workflow_dispatch` | `cleanup` on `[self-hosted, yeon-arm]` | prunes old `sha-*` GHCR package versions, preserves `latest` / `buildcache` | `GITHUB_TOKEN`; `packages: write` | GHCR package retention; no build cache deletion beyond package versions | **Do not touch unless registry retention task** |

## Minimal workflow surface for NEXA

Default NEXA social-behavior work should avoid workflow edits. Use local verification first:

```bash
./scripts/nexa-verify.sh docs
./scripts/nexa-verify.sh central
```

If a future NEXA task truly needs CI wiring, the minimum candidate workflows are:

1. `central-server-ci.yml` — for new central-server Kotlin tests, ArchUnit rules, Cucumber/Testcontainers gates, deterministic test enforcement, or Testcontainers cleanup policy.
2. `provider-agent-ci.yml` — only when NEXA work changes provider-agent, docs link checks, i18n, packaging checks, or shared scripts already covered by that workflow.

Do not add deploy, release, signing, package publication, registry cleanup, or self-hosted production behavior to a NEXA feature task unless the task explicitly names that workflow and includes rollback/approval evidence.

## Workflows to avoid by default

NEXA implementation tasks must not edit these without an explicit release/deploy/ops requirement:

- `agent-autorelease.yml`
- `agent-build.yml`
- `ai-rag-rebuild.yml`
- `central-deploy.yml`
- `central-release.yml`
- `central-server-deploy.yml`
- `central-server-image.yml`
- `ghcr-cleanup.yml`

Reason: these workflows have write permissions, release tags, self-hosted runner state, GHCR package writes, signing credentials, or production-ish compose/deploy effects. They are not needed to add or validate central-server social behavior logic.

## Cache and runner notes

- No workflow currently uses `actions/cache` directly.
- `nexa-security-scan.yml` has a `changes` preflight job. Pull requests run only the security jobs whose dependency surface changed; scheduled and manual runs still perform the full security scan.
- `central-server-ci.yml` runs Testcontainers cleanup after the Docker-dependent integration job. Cleanup is limited to resources labeled `org.testcontainers=true`.
- `central-deploy.yml` and `central-server-image.yml` use `docker/build-push-action@v6`; the YAML does not declare `cache-from` or `cache-to`.
- `central-deploy.yml`, `agent-build.yml` `publish-central`, `ai-rag-rebuild.yml`, and deprecated `central-server-deploy.yml` use self-hosted runners. Treat these as operational surfaces, not ordinary feature-test surfaces.
- `ai-rag-rebuild.yml` is docs-triggered on `main` (`docs/**/*.md`) and can commit generated metadata. Docs-only NEXA changes should not modify this workflow unless the RAG trigger policy itself is being changed.

## Verification for this snapshot

```bash
./scripts/nexa-verify.sh docs
```

The docs scope now validates the task graph, checks the generated central package graph snapshot, checks links, and runs `git diff --check`.
