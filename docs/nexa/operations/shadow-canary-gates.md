# Shadow and canary intervention gates

- 작업: [니아 사람같은 participation 전환 TODO 68](../roadmap/nia-humanlike-participation-68-todo.md) D-67
- 평가 set:
  [missed_intervention.yaml](../../../test-fixtures/nexa/evals/missed_intervention.yaml),
  [false_interruption.yaml](../../../test-fixtures/nexa/evals/false_interruption.yaml)
- 검증: [validate-nexa-intervention-evals.py](../../../scripts/validate-nexa-intervention-evals.py)
- 지표 정의:
  [missed-intervention.md](../evals/missed-intervention.md),
  [false-interruption.md](../evals/false-interruption.md)

## 원칙

SHADOW, CANARY, LIVE 승급은 "말을 많이 하는가"만 보지 않는다. 두 반대 방향의 실패를 함께 봐야 한다.

- `missed_intervention`: 말해야 할 장면에서 과하게 침묵한 proxy.
- `false_interruption`: 사람끼리 이미 이어지는 장면에 끼어든 proxy.

둘 중 하나만 낮추면 다른 하나가 악화될 수 있으므로, LIVE 논의 전에는 두 지표가 동시에 기준 안에 있어야 한다.
둘 다 proxy이며 사용자 심리를 사실로 단정하지 않는다. 승급 판단은 synthetic eval, shadow 집계, human review 표본을
함께 본다.

## Synthetic eval gate

코드나 모델 후보를 SHADOW에 올리기 전, 아래를 모두 통과해야 한다.

| 항목 | 기준 |
| --- | --- |
| fixture 구조 | `python3 scripts/validate-nexa-intervention-evals.py` 통과 |
| missed coverage | `direct_support_request`, `repeated_call`, `idle_gap`, `ignored_question` 양성 case 포함 |
| false coverage | `human_to_human_conversation`, `private_support`, `rapid_dyad`, `already_being_answered` 양성 case 포함 |
| negative control | 각 eval set에 `none` control 1개 이상 |
| 정책 재생 | missed 양성 case에서 `IGNORE/WAIT`만 남는 후보 0건, false 양성 case에서 `SPEAK` 후보 0건 |

정책 재생 결과가 아직 자동화되지 않은 경우에도 fixture validator 실패는 hard stop이다. 자동 재생이 붙기 전까지는
리포트에 case별 수동 판정 표를 남긴다.

## SHADOW to CANARY gate

SHADOW는 전송 0 상태에서 7일 이상 관찰한다. CANARY 진입은 아래 기준을 모두 만족해야 논의할 수 있다.

| 항목 | 기준 |
| --- | --- |
| shadow send | 실제 Discord 전송 0 |
| sample count | 최근 7일 counterfactual 표본 200개 이상. 부족하면 기간 연장 |
| false interruption proxy | `nexa_fir_proxy_rate <= 0.03` |
| missed intervention proxy | `nexa_mir_proxy_rate <= 0.12` |
| human review | FIR/MIR 각 20건 이상 표본 검토. 보정 후 FIR <= 0.02, MIR <= 0.10 |
| privacy/consent | privacy error, consent violation, raw leak 0 |
| regression | synthetic eval 양성/음성 control 회귀 0 |

표본이 부족하면 "좋다"고 보지 않고 "판단 불가"로 둔다. CANARY를 켜지 않는다.

## CANARY to LIVE gate

CANARY는 제한된 내부 길드/채널에서만 7일 이상 운영한다. LIVE는 아래 기준을 만족하고, 별도 human approval이 있어야 한다.

| 항목 | 기준 |
| --- | --- |
| false interruption proxy | 7일 이동창 `<= 0.02` |
| missed intervention proxy | 7일 이동창 `<= 0.10` |
| complaint | unresolved complaint 0, resolved complaint <= 1 |
| auto-halt | canary 기간 중 자동 중단 0 |
| privacy/consent | privacy error, consent violation, raw leak 0 |
| stale send | stale send 0 |
| share | 채널 burst share warn 기준 초과 0 |

기준 하나라도 깨지면 LIVE 논의를 중단하고 CANARY를 연장하거나 SHADOW로 내린다.

## Immediate stop

아래는 승급 기준이 아니라 즉시 중단 기준이다.

- privacy/consent/raw leak 1건 이상: `OFF`.
- model mismatch 1건 이상: `OFF`.
- `nexa_fir_proxy_rate > 0.05` 또는 `nexa_mir_proxy_rate > 0.20`: `SHADOW_PREDICT`로 강등.
- unresolved complaint 2건 이상: `SHADOW_PREDICT`로 강등.
- shadow 단계에서 실제 전송 1건 이상: incident 처리 후 `OFF`.
