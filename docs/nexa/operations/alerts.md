# NEXA 운영 alert (P18-T011/T012)

NEXA 운영 alert 의 SSOT. alert 는 **사람 확인(human confirm)** 과 **자동 강등/kill(auto downgrade)** 을 명확히 구분한다(P18-T011 acceptance). 자동 강등은 운영 자동화가, 실제 kill 발동은 사람이 누른다(human_gate). 평가 로직은 `central-server/.../global/observability/Nexa*AlertEvaluator.kt`(순수 함수)에 있고, 실제 알림 발송은 운영 배포에서만 한다(이 작업에서는 발송 금지).

## 과다 발화 alert (P18-T011) — `NexaOverTalkAlertEvaluator`

NEXA 가 **너무 많이 말하는** 신호. 각 신호는 warn(사람 확인)·critical(자동 강등) 두 임계를 가진다.

| 신호 | 의미 | warn(사람 확인) | critical(자동 강등) |
| --- | --- | --- | --- |
| `SHARE_RATIO` | 최근 창 NEXA burst 점유율 | ≥ 0.35 | ≥ 0.5 |
| `CONSECUTIVE_BURSTS` | 인간 사이 NEXA 연속 burst 수 | ≥ 3 | ≥ 5 |
| `MENTION_RESPONSE_SPIKE` | 분당 mention 응답 수 | ≥ 10 | ≥ 20 |
| `QUEUE_BACKLOG` | 전송 대기 queue 크기 | ≥ 20 | ≥ 50 |

- warn → `HUMAN_CONFIRM`: 대시보드에 표시하고 운영자가 판단한다(자동 조치 없음).
- critical → `AUTO_DOWNGRADE`: 즉시 lane 강등(LIVE→CANARY→OFF) 또는 kill switch 발동 **권고**. 자동화가 강등을, 영구 kill 은 사람이 확인한다.
- 어떤 신호도 임계를 안 넘으면 alert 없음(정상).

## 모델 오류·fallback alert (P18-T012) — `NexaModelErrorAlertEvaluator`

policy timeout·schema mismatch·fallback-to-silent 의 **지속 시간/비율** 기준. **fallback 자체는 정상 안전 동작일 수 있어** 단일 이벤트로는 절대 alert 하지 않는다(acceptance T012).

- alert 조건(동시 만족): 표본 ≥ 20 **그리고** 실패 비율(`(timeout+mismatch+fallback)/total`) ≥ 0.2 **그리고** 지속 ≥ 5분.
- `AUTO_DOWNGRADE` 격상: 실패 비율 ≥ 0.5 또는 지속 ≥ 15분.
- 잠깐의 spike·표본 부족은 무시한다(noise 회피).

## 길드별 kill switch (P18-T013)

alert 의 critical(`AUTO_DOWNGRADE`)이나 운영자 판단으로 **특정 길드의 NEXA 를 즉시 끈다**. 발동 즉시 신규 결정·예약·전송이 중단되고 이미 생성된 pending content 까지 취소되며 audit 가 남는다(`GuildKillSwitchService`). 상세는 코드 KDoc 과 `operations/metrics.md` 참조. **실제 발동은 운영에서만 한다(human_gate).**
