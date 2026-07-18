# Current LLM Flow Baseline

Generated: 2026-07-18
Scope: central-server text LLM routing, NIA judgment and speech, image prompt review/translation, and Provider Agent boundaries.

## Runtime truth

- The central external text LLM implementation is `OpenAiCloudLlm`.
- Every central cloud path defaults to `gpt-5.6-luna`.
- Every OpenAI Responses request sends `reasoning.effort=none` and `store=false`.
- Only `gpt-*` model IDs can take the central direct-cloud branch.
- There is no Z.AI credential, endpoint, model advertisement, or runtime client in Provider Agent.
- Local Ollama models continue through `ProviderSession.sendInfer`.

## Central request path

```text
Discord request
  -> admission policy and quota checks
  -> local Ollama provider when an allowed local model is selected
  -> otherwise OpenAI Responses API with gpt-5.6-luna
  -> response parsing, usage recording, and Discord rendering
```

The OpenAI key is read from `central.cloud.openai-api-key` (`OPENAI_API_KEY`). Deployment passes it as the
Docker config-tree secret `/run/secrets/central.cloud.openai-api-key`. If the key is absent, the direct cloud
path is disabled and does not fall back to Z.AI.

## NIA path

Participation judge, complete-action evaluation, speech generation, social appraisal, memory extraction,
admin tool calling, and `/질문` share the same OpenAI adapter. Model settings may be separated for operational
rollback, but all defaults are `gpt-5.6-luna` and the adapter still forces reasoning `none`.

Managed NIA few-shot examples are loaded from the active database version. Participation examples influence
the judge; examples with `expectedReplies` are also rendered into the speech identity prompt. Static dad-joke
examples are not bundled into the persona.

## Image path

```text
user prompt
  -> central OpenAI safety review (fail closed)
  -> central OpenAI translation
  -> central or Provider Agent pixel backend
```

Provider Agent accepts an image request only when `imagePolicy.preTranslated=true`. It never calls an external
text LLM and only performs pixel generation through ComfyUI, Stability, or RunPod.

## Automated evidence

- `OpenAiCloudLlmTest` verifies `/responses`, Luna, `reasoning.effort=none`, `store=false`, and tool conversion.
- `RequestOrchestratorTest` verifies only Luna takes the central direct-cloud branch after policy checks.
- Provider Agent tests verify missing `preTranslated` fails closed and no provider-side cloud key is stored.
- Default gates use mocked HTTP clients. Live Discord speech and production API calls remain human-gated.
