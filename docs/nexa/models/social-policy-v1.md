# Model Card — Policy v1 (`policy-v1-fixture`)

> 이 문서는 manifest 에서 자동 생성된다. 수동으로 수정하지 마라(수치 드리프트 금지, P11-T024).
> 재생성: `nexa_policy.reporting.model_card.render_model_card`.

## 식별 (Identity)
- model_id: `policy-v1-fixture`
- model_version: `policy-v1-fixture`
- dataset_id: `nexa-ds-fixture`
- artifact_sha256: `55c1fca49fad7f3eab4fef8167c4723ee5da0905e52fda04e6cfa5abcda1c4e2`
- feature_schema_version: `2`
- calibration_version: `cal-1`
- onnx_opset: `17`

## 학습 데이터 (Training Data)
운영 데이터 미접근. 합성 fixture(`nexa_policy.datasets.make_synthetic_dataset`, seed 결정론)로 학습한다 — P10 라벨 의미(action/target/delay/burst/social_act)와 P08 feature 카탈로그를 미러하되 실제 메시지/식별자는 없다. 실제 학습 시에도 동의(opt-in)·관찰 가능 신호만(P10 export 경계) 사용한다.

## 지표 (Metrics)
  - `balanced_accuracy`: 0.5000
  - `ece`: 0.1000
  - `false_ignore_rate`: 0.2000

## Calibration
- calibration_version: `cal-1`. P09 calibration(EXP-talkativeness logit 보정)과 일관. ECE/Brier 로 과신 여부를 검증한다.

## 실패 유형 (Failure Modes)
- 소형/저tempo 또는 특정 언어 길드에서 평균은 좋아도 붕괴할 수 있다(서버 간 일반화 분석, T022). 최악 부분군이 floor 미만이면 채택 금지.
- 클래스 불균형(다수 IGNORE)으로 SPEAK recall 이 낮을 수 있다(FIR/MIR 모니터링).
- calibration 미보정 시 확률이 과신될 수 있다(ECE/Brier 로 검증, P09 calibration 일관).
- 약지도 social act 라벨 노이즈로 act head 신뢰도가 낮을 수 있다(low-confidence weight 제외).

## 사용 금지 / 금지 추론 (Prohibited Use)
observable-state-policy(P09) 위반 추론을 **사용 금지** 한다: 내면 상태·정체성·민감 속성(정치/종교/건강/성적지향 등) 추론, 원문 텍스트·실제 식별자 입력, 특정 member ID 를 feature 로 직접 사용. feature 는 관찰/집계 신호(OBSERVABLE/AGGREGATE)뿐이다(FeatureCatalog).

## LIVE 승인 전제 (Prerequisites for LIVE Approval)
- **Python-JVM parity**: 같은 ONNX·입력에서 head 출력이 허용오차(1e-4) 내 일치(T019 golden parity 통과).
- **baseline 대비 개선**: P09 baseline 들보다 balanced accuracy·FIR/MIR 가 의미 있게 낫다.
- **일반화**: 최악 부분군이 collapse_floor 이상(평균만 좋은 기만 모델 아님, T022).
- **calibration**: ECE/Brier 가 기준 이하(과신 아님).
- **레지스트리 승인**: ShadowModelRegistry 에서 REGISTERED→SHADOW→APPROVED 를 거친 artifact 만 LIVE 자격(미승인 artifact LIVE 선택 불가, T020). 자동 승격 없음 — 독립 리뷰(human gate, T025) 필수.
- **운영 적용은 별도**: 본 카드 작성·승인 기준 충족이 곧 배포가 아니다(ShadowMode CANARY→LIVE 는 길드별 승인).

## Known Limitations
- fixture 는 합성이라 절대 성능 수치는 운영 일반화를 보장하지 않는다(상대 비교·파이프라인 검증용).
- target head 는 추상 후보 슬롯이라 JVM 어댑터는 장면 ID 부재 시 none 대상으로 복원한다(구체 대상 결정은 상위 단계).
- 7일 shadow 비교(T021)·독립 리뷰(T025)는 운영 게이트라 본 산출물 범위 밖이다.
