#!/usr/bin/env bash
set -euo pipefail

BASE_REF="${RAG_BOUNDARY_BASE_REF:-HEAD}"

generated_regex='^rag/(bm25/corpus\.jsonl|meta\.db)$'
allowed_source_regex='^(rag/|docs/|docker/rag/|docker-compose\.qdrant\.yml|scripts/rag\.sh|\.github/workflows/ai-rag-rebuild\.yml)$'

changed_files="$(
  {
    git diff --name-only "$BASE_REF" -- || true
    git diff --name-only --cached -- || true
  } | sort -u
)"

if [ -z "$changed_files" ]; then
  exit 0
fi

generated_changed="$(printf '%s\n' "$changed_files" | grep -E "$generated_regex" || true)"
if [ -z "$generated_changed" ]; then
  exit 0
fi

non_generated_changed="$(printf '%s\n' "$changed_files" | grep -Ev "$generated_regex" || true)"
if [ -z "$non_generated_changed" ]; then
  exit 0
fi

disallowed_mix="$(printf '%s\n' "$non_generated_changed" | grep -Ev "$allowed_source_regex" || true)"
if [ -n "$disallowed_mix" ]; then
  cat >&2 <<'MSG'
RAG generated index files must not be mixed with ordinary code/config changes.

Keep these generated files in the dedicated AI RAG rebuild commit/workflow:
MSG
  printf '%s\n' "$generated_changed" >&2
  cat >&2 <<'MSG'

Move these unrelated changes to a separate PR/commit before including generated RAG output:
MSG
  printf '%s\n' "$disallowed_mix" >&2
  exit 1
fi

cat >&2 <<'MSG'
RAG generated files changed together with RAG/docs source files.
This is allowed only for the dedicated AI RAG rebuild lane; ordinary feature PRs should leave generated index files out.
MSG
