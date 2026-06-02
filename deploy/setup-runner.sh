#!/usr/bin/env bash
# deploy/setup-runner.sh — Register THIS macOS host as a GitHub Actions
# self-hosted runner for the repo, with the labels the deploy workflow expects.
#
# Prereqs on the host:
#   - gh CLI authenticated (gh auth status) OR a registration token passed in
#   - Docker Desktop installed and running (the deploy uses `docker compose`)
#
# Usage:
#   REPO=Hyeonjun0527/discord-ai-network-bot bash deploy/setup-runner.sh
#   # optional: RUNNER_VERSION=2.323.0 RUNNER_DIR="$HOME/actions-runner" LABELS="self-hosted,macOS,ARM64"
#
# The deploy workflow targets runs-on: [self-hosted, macOS, ARM64].
set -euo pipefail

REPO="${REPO:-Hyeonjun0527/discord-ai-network-bot}"
RUNNER_DIR="${RUNNER_DIR:-$HOME/actions-runner}"
LABELS="${LABELS:-self-hosted,macOS,ARM64}"
RUNNER_NAME="${RUNNER_NAME:-$(hostname -s)-arm64}"

echo "[runner] repo=${REPO} dir=${RUNNER_DIR} labels=${LABELS} name=${RUNNER_NAME}"

# ── Architecture (Apple Silicon → osx-arm64) ─────────────────────────────────
case "$(uname -m)" in
  arm64) RUNNER_ARCH="osx-arm64" ;;
  x86_64) RUNNER_ARCH="osx-x64" ;;
  *) echo "지원하지 않는 아키텍처: $(uname -m)" >&2; exit 1 ;;
esac

# ── Resolve the latest runner version unless pinned ──────────────────────────
if [ -z "${RUNNER_VERSION:-}" ]; then
  RUNNER_VERSION="$(gh api repos/actions/runner/releases/latest --jq '.tag_name' | sed 's/^v//')"
fi
echo "[runner] version=${RUNNER_VERSION} arch=${RUNNER_ARCH}"

# ── Get a registration token (short-lived) via gh ────────────────────────────
REG_TOKEN="${REG_TOKEN:-$(gh api -X POST "repos/${REPO}/actions/runners/registration-token" --jq '.token')}"
[ -n "${REG_TOKEN}" ] || { echo "registration token 획득 실패" >&2; exit 1; }

# ── Download & extract the runner ────────────────────────────────────────────
mkdir -p "${RUNNER_DIR}"
cd "${RUNNER_DIR}"
TARBALL="actions-runner-${RUNNER_ARCH}-${RUNNER_VERSION}.tar.gz"
if [ ! -f "config.sh" ]; then
  echo "[runner] downloading ${TARBALL} ..."
  curl -fsSL -o "${TARBALL}" \
    "https://github.com/actions/runner/releases/download/v${RUNNER_VERSION}/${TARBALL}"
  tar xzf "${TARBALL}"
  rm -f "${TARBALL}"
fi

# ── Configure (idempotent: replace if already registered) ────────────────────
./config.sh \
  --url "https://github.com/${REPO}" \
  --token "${REG_TOKEN}" \
  --name "${RUNNER_NAME}" \
  --labels "${LABELS}" \
  --unattended \
  --replace

# ── Install & start as a launchd service (auto-starts on login/boot) ─────────
./svc.sh install
./svc.sh start
./svc.sh status

echo "[runner] 완료. GitHub → Settings → Actions → Runners 에서 온라인 상태를 확인하세요."
echo "[runner] 중지/삭제: cd ${RUNNER_DIR} && ./svc.sh stop && ./svc.sh uninstall && ./config.sh remove --token <removal-token>"
