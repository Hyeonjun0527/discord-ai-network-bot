# 기준선 비교 리포트 템플릿 (NEXA-P09-T022)

- 작업: NEXA-P09-T022 (`kind: documentation`, `human_gate: false`) · 상위: [participation-context](../architecture/participation-context.md)
- 입력 지표 구현:
  [`CalibrationMetrics.kt`](../../../central-server/src/main/kotlin/com/discordassistant/central/participation/application/evaluation/CalibrationMetrics.kt),
  [`InterventionProxies.kt`](../../../central-server/src/main/kotlin/com/discordassistant/central/participation/application/evaluation/InterventionProxies.kt),
  [`DistributionComparison.kt`](../../../central-server/src/main/kotlin/com/discordassistant/central/participation/application/evaluation/DistributionComparison.kt),
  [`MentionResponseAudit.kt`](../../../central-server/src/main/kotlin/com/discordassistant/central/participation/application/evaluation/MentionResponseAudit.kt),
  [`ShadowDailyReportService.kt`](../../../central-server/src/main/kotlin/com/discordassistant/central/participation/application/reporting/ShadowDailyReportService.kt)
- 관련 proxy 정의: [false-interruption](../evals/false-interruption.md), [missed-intervention](../evals/missed-intervention.md)

이 문서는 **템플릿** 이다(특정 실측 결과 아님 — 표 셀은 채울 자리 `<…>`). shadow 관찰이 모이면 정책별로 같은 표에
값을 채워 비교한다. 운영 데이터 금지 — 허가된 테스트 길드 관찰만(P09-T024 게이트).

## 비교 대상 기준선 (모두 한 표에)

acceptance(T022): **항상 침묵·멘션 응답·고정확률·burst-aware·legacy 를 같은 표로 비교**한다.

| 코드 | 정책 | 한 줄 설명 |
| --- | --- | --- |
| `always-silent` | 항상 침묵 | 절대 발화하지 않는 하한 기준선(안전 상한·유용성 하한). |
| `mention-response` | 멘션 응답 | 직접 mention 에만 응답(단순 규칙 기준선). |
| `fixed-prob` | 고정 확률 | 장면 무관 고정 SPEAK 확률(보정 안 된 기준선). |
| `burst-aware` | burst-aware | burst/tempo/관계 feature 로 보정한 후보 정책. |
| `legacy` | 레거시 | 기존 auto-respond/채널AI 행동(현행 비교 기준). |

## 비교 표 (한 지표만으로 승자 금지)

acceptance(T022): **한 지표만으로 승자를 정하지 않고 calibration·분포·안전 지표를 포함**한다. 아래 표는 **여러 축**을
나란히 둔다 — 어느 정책도 모든 열에서 동시에 이기기 어렵고(특히 안전 ↔ 유용성 trade-off), 종합 판단을 강제한다.

### 1) 정확도·보정 (calibration)

| 정책 | 표본 수 | SPEAK share | Brier ↓ | ECE ↓ | bin CI 폭(평균) |
| --- | ---: | ---: | ---: | ---: | ---: |
| `always-silent` | `<n>` | `<%>` | `<…>` | `<…>` | `<…>` |
| `mention-response` | `<n>` | `<%>` | `<…>` | `<…>` | `<…>` |
| `fixed-prob` | `<n>` | `<%>` | `<…>` | `<…>` | `<…>` |
| `burst-aware` | `<n>` | `<%>` | `<…>` | `<…>` | `<…>` |
| `legacy` | `<n>` | `<%>` | `<…>` | `<…>` | `<…>` |

- Brier/ECE 는 [CalibrationMetrics] 출력(낮을수록 정확/보정). **표본 수와 bin 신뢰구간(CI)** 을 함께 본다 — 표본
  적은 정책의 점추정에 속지 않는다(acceptance T017).

### 2) 분포 (길드/채널 문화 대비)

| 정책 | speakRate Δ vs 기준선 | median delay Δ | action mix L1 거리 |
| --- | ---: | ---: | ---: |
| `always-silent` | `<…>` | `<…>` | `<…>` |
| `mention-response` | `<…>` | `<…>` | `<…>` |
| `fixed-prob` | `<…>` | `<…>` | `<…>` |
| `burst-aware` | `<…>` | `<…>` | `<…>` |
| `legacy` | `<…>` | `<…>` | `<…>` |

- [DistributionComparison] 출력. 외부 label 은 **저카디널리티 cohort 버킷** 만(원본 ID 금지 — acceptance T018).
  0 에 가까울수록 그 cohort 문화 템포에 맞음.

### 3) 안전 (proxy·멘션 무응답)

| 정책 | FIR ↓ (T015) | MIR ↓ (T016) | 멘션 무시율 vs 인간 무응답률 | 안전 경보 |
| --- | ---: | ---: | --- | --- |
| `always-silent` | `<…>` | `<높음 예상>` | `<…>` | `<…>` |
| `mention-response` | `<…>` | `<…>` | `<…>` | `<…>` |
| `fixed-prob` | `<…>` | `<…>` | `<…>` | `<…>` |
| `burst-aware` | `<…>` | `<…>` | `<…>` | `<…>` |
| `legacy` | `<…>` | `<…>` | `<…>` | `<…>` |

- FIR(False Interruption)·MIR(Missed Intervention)는 [InterventionProxies] 출력 — **서로 반대 방향**이라 둘을 함께
  본다(FIR↓ 를 위해 발화를 줄이면 MIR↑). proxy 는 **상한** 으로 읽는다(오탐 가능 — eval 문서 참조).
- 멘션 무응답은 [MentionResponseAudit] — 정책 멘션 무시율이 인간 무응답률보다 안전 마진 이상 높으면 **경보**
  (acceptance T019, 안전 회귀).

## 판정 규칙 (acceptance)

- **단일 지표 승자 금지**: 예) `always-silent` 는 FIR=0 으로 보이지만 MIR 가 최악이고 유용성이 0 이다. `fixed-prob`
  는 SPEAK share 가 높아도 보정(ECE)이 나쁠 수 있다. **세 축(정확도·분포·안전)을 모두** 본 뒤 trade-off 를 명시해
  종합 판단한다.
- **표본·CI 동반**: 표본 적은 cell 은 CI 폭으로 불확실성을 드러내고, 충분 표본 전에는 승패를 확정하지 않는다.
- **경보 우선**: 안전 경보(멘션 무시 과다 등)가 켜진 정책은 다른 지표가 좋아도 LIVE 승격 후보에서 제외한다.
- 최종 승격은 human gate(P09-T024 이후) — 이 표는 **근거** 이지 자동 결정이 아니다.
