# 외부 benchmark·재현 패키지 (NEXA-P19-T022)

- 작업: NEXA-P19-T022 (`kind: documentation`, `human_gate: true`, `risk: high`) · 선행: NEXA-P19-T021
- 코드: [`ml/social-policy/README.md`](../../../ml/social-policy/README.md),
  [`datasets.py`](../../../ml/social-policy/src/nexa_policy/datasets.py),
  [`metrics.py`](../../../ml/social-policy/src/nexa_policy/metrics.py),
  [`data/privacy.py`](../../../ml/social-policy/src/nexa_policy/data/privacy.py)
- 상위: [reproducibility](../../../ml/social-policy/src/nexa_policy/reproducibility.py),
  [reward-contract](./reward-contract.md), [continuous-time-policy](./continuous-time-policy.md)

## 목적

NEXA participation 정책 평가를 **외부에서 재현 가능**한 형태로 정리한다: 개인정보 없는 합성 fixture, metric 코드,
contract(스키마·계약). 외부 검토자가 같은 코드를 돌려 같은 수치를 얻을 수 있어야 한다(deliverable T022).

## 재현 패키지 구성

| 구성 | 위치 | 내용 |
| --- | --- | --- |
| **합성 fixture** | `datasets.make_synthetic_dataset`, `make_synthetic_*` (seed 결정론) | 신호·카운트·시각만. 원문·user id 없음 |
| **metric 코드** | `metrics.py`(balanced acc·FIR·MIR·Brier·ECE), `rl/ope.py`(OPE+CI), `eval/*` | 평가 지표 전부 numpy/sklearn |
| **contract** | `contracts/event_sequence.schema.json`(`contains_raw_content:false`), `data/export/boundary.py` | 입력 스키마·export 경계 |
| **재현성** | `reproducibility.py`(seed·환경 캡처) | 같은 seed/config → 같은 수치 |

## 재현 절차(외부 검토자)

```bash
cd ml/social-policy
python -m venv .venv && source .venv/bin/activate
pip install -e .[ml]
python -m pytest -q          # 모든 acceptance 테스트(합성 fixture)
ruff check src tests && mypy src
```

- 모든 테스트는 합성 fixture·결정론이라 외부 환경에서 동일하게 재현된다(운영 데이터·네트워크 불필요).
- torch 미사용 — numpy/sklearn 만으로 신경망·survival·OPE 까지 재현된다(경량·결정론).

## acceptance — 실제 Discord 원문·사용자 식별자는 공개 artifact 에 포함되지 않는다

구조적 가드(공개 패키지에 PII 가 들어갈 수 없음):

1. **스키마 고정**: `event_sequence.schema.json` 이 `contains_raw_content: false`. 원문 컬럼 자체가 스키마에 없다.
2. **export 경계 fail-closed**: `data/export/boundary.py` 가 원문/원본 id 컬럼을 거부한다(`training_eligible`·
   가명만 통과).
3. **privacy 검사**: `data/privacy.py` 가 fixture·산출물에 raw content·실제 snowflake 패턴이 없는지 단언한다
   ([test_privacy.py] 가 회귀 방지).
4. **가명 전용**: 작성자는 guild-scope 가명(`actor_pseudonym`)뿐 — cross-guild 식별 불가(ADR 0010).

따라서 공개 fixture·코드에는 실제 Discord 메시지 원문도, 실제 사용자 식별자도 존재하지 않는다. 합성 데이터는
라벨 의미(P10)와 feature 카탈로그(P08)를 미러할 뿐이다.

## 한계

- 합성 fixture 의 절대 수치는 운영 일반화를 보장하지 않는다(상대 비교·파이프라인·방법 재현용).
- 외부 공개 범위·라이선스는 운영 게이트(human_gate, T023 이후)에서 확정한다 — 본 문서는 재현 패키지의 경계와
  PII 부재 가드를 고정한다.
