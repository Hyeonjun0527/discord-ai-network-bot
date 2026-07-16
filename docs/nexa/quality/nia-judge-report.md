# NIA Judge Few-Shot Seed Report

- fixture: `test-fixtures/nexa/quality/nia-fewshot-seed.yaml`
- status: **PASS**
- totalExamples: 40
- hardAmbiguousExamples: 7

## Action Coverage

| Action | Count | Expected |
| --- | ---: | ---: |
| SPEAK | 10 | 10 |
| WAIT | 9 | 9 |
| REACT | 6 | 6 |
| IGNORE | 10 | 10 |
| CANCEL | 5 | 5 |

## Metric Gates

| Metric | Status | Failed Example IDs |
| --- | --- | --- |
| action_correctness | PASS | - |
| over_talk | PASS | - |
| under_talk | PASS | - |
| stale_memory_override | PASS | - |
| ambiguous_contrast | PASS | - |
| privacy | PASS | - |
| composition | PASS | - |
| coverage | PASS | - |
| schema | PASS | - |

## Failures

- none
