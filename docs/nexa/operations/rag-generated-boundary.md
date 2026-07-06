# RAG generated index boundary

The tracked RAG index files are generated artifacts:

- `rag/bm25/corpus.jsonl`
- `rag/meta.db`

Do not mix those files with ordinary code, policy, workflow, or application changes. Source changes may update
docs or RAG builder code, but generated index output should be produced by the dedicated AI RAG rebuild lane.

Enforcement:

```bash
scripts/check-rag-generated-boundary.sh
```

`./scripts/nexa-verify.sh docs` runs the same check. It fails when generated RAG files are changed together
with unrelated code/config files. The only acceptable generated-output lane is the AI RAG rebuild workflow,
which commits `rag/bm25/corpus.jsonl` and `rag/meta.db` after `scripts/rag.sh build-local` and
`scripts/rag.sh eval`.

If a PR changes both RAG source and application code, split it:

1. land the application/source change without generated index files,
2. let `.github/workflows/ai-rag-rebuild.yml` produce the generated metadata commit on `main`,
3. review generated metadata separately if needed.
