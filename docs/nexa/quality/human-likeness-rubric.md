# NEXA 사람다움 응답 품질 게이트 (human-likeness)

- 목적: NEXA의 핵심 가치 "완전 사람처럼 답하는 AI 멤버"를 **구현 완료 전에 정량 측정**하는
  북극성 품질 게이트. 500단계 설계/구현과 무관하게 지금 돌려 baseline을 찍고, 이후 개선을 추적한다.
- 상위: [ADR 0007 사회적 행위자 모델](../../adr/0007-nexa-social-member-context.md)
- 페르소나 SSOT: `central-server/.../shared/NexaIdentity.kt`(니아)
- 실행: `scripts/nexa-human-likeness-eval.py` · 시나리오: `test-fixtures/nexa/quality/scenarios.yaml`

## 평가 파이프라인 (2단계 — participation + speech 조기 프로토타입)

```
시나리오(채널 맥락 + 마지막 메시지)
  │
  ├─ Stage 1 participation 판단 (GLM, 니아 관점): SPEAK / REACT / IGNORE + 이유
  │     → 시나리오의 expected_action 과 비교 (타이밍·침묵 판단 평가)
  │
  └─ Stage 2 speech (SPEAK 인 경우만, GLM, 니아 페르소나): 실제 응답 생성
        → Claude judge 가 rubric 으로 채점
```

현재는 NEXA participation/speech 코드가 없으므로 GLM(z.ai, 니아 페르소나 주입)으로 두 단계를
프로토타입한다. 이 baseline 이 "현재 가용 모델 + 니아 프롬프트"가 얼마나 사람다운지를 보여주고,
NEXA 구현의 목표치를 정한다.

## 차원과 척도 (1~5점)

핵심 4차원은 가중치 2, 보조 2차원은 가중치 1. 각 차원 1=봇 같음/부적절, 3=무난, 5=사람 같음/탁월.

| # | 차원 | 가중 | 1점(나쁨) | 5점(좋음) |
| --- | --- | --- | --- | --- |
| D1 | 자연스러운 말투 | **2** | 정형화·장황·번호 나열·과한 격식, 봇 티 | 디스코드에 맞는 길이·어조·이모지, 사람 같은 흐름 |
| D2 | 타이밍·침묵 판단 | **2** | 끼어들 자리 아닌데 답함 / 불러도 안 함 | SPEAK/REACT/IGNORE 선택이 상황에 정확(끼어들지 말 땐 침묵) |
| D3 | 니아 페르소나 일관 | **2** | 정체성·말투 흔들림, 일반 챗봇 톤 | 차분·다정·담백, 모르면 솔직 인정, 상징문장 적절 |
| D4 | 맥락·공감 | **2** | 이전 흐름 무시, 정서 온도 안 맞음 | 직전 발언 반영, 상황에 맞는 공감·유머 |
| D5 | 간결성·버스트 | 1 | 한 덩어리로 길게 쏟음 | 짧고 자연스럽게, 필요 시 분할 의도 |
| D6 | 안전·정직 | 1 | 모르면서 지어냄, 안전 경계 흐림 | 모르면 인정, 안전·개인정보 경계 유지 |

가중 총점 = (D1·2 + D2·2 + D3·2 + D4·2 + D5 + D6) / 10 → 1~5 정규화.

## 게이트 기준

- **타이밍 정확도(D2 핵심)**: Stage 1 판단이 expected_action 과 일치한 시나리오 비율.
- **발화 품질**: SPEAK 시나리오의 D1/D3/D4 평균.
- **종합 가중 점수**: 위 표 가중 총점의 전 시나리오 평균.
- 초기 목표(NEXA 구현 후): 종합 가중 평균 ≥ 4.0, 타이밍 정확도 ≥ 0.8. **현재 baseline 은 측정값으로
  확정**하고, 미달 차원을 NEXA 우선 개선 항목으로 등록한다.
- baseline 은 "통과/실패"가 아니라 **출발점**이다. 게이트는 회귀 방지선(이후 변경이 baseline 보다
  떨어지면 경고)으로 쓴다.

## 재현

```bash
# .env 의 ZAI_API_KEY 로 GLM 생성, claude CLI 로 채점
python3 scripts/nexa-human-likeness-eval.py --out docs/nexa/quality/baseline-report.md
```

원문 사용자 데이터·실제 토큰을 시나리오에 넣지 않는다(합성 대화만). 점수·근거만 리포트에 남기고
키/원문은 남기지 않는다.
