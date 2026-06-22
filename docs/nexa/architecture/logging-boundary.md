# 경계 계약: requestlog · participation decision log

- 작업: NEXA-P01-T012 · 상위: [ADR 0007](../../adr/0007-nexa-social-member-context.md)
- 근거 기준선: [logging-baseline.md](../security/logging-baseline.md)(T017),
  [current-llm-flow.md](../baseline/current-llm-flow.md)
- 관련 계약: [participation-context.md](./participation-context.md)

## 목적

두 종류의 로그를 분리해 **원문 Discord 내용 없이** 추적 가능하게 한다.

| 로그 | 소유 | 기록 대상 |
| --- | --- | --- |
| requestlog | routing/requestlog | 외부 **모델 요청**의 결과·상태·쿼터·제공자(기존 `ai_request`/`usage_log`) |
| decision log | participation | **사회행동 결정**(IGNORE/WAIT/REACT/SPEAK/CANCEL)과 근거 feature |

## 연결 규칙 (acceptance)

- 두 로그는 **correlation ID**로 연결한다 — 한 참여 결정이 모델 호출로 이어지면 동일 correlation
  ID를 공유한다. SPEAK가 아니면 모델 호출(requestlog)이 없을 수 있다.
- 두 로그 어디에도 **원문 메시지/프롬프트/모델 응답 본문**을 저장하지 않는다
  ([logging-baseline.md](../security/logging-baseline.md)의 redaction contract 준수):
  금지 필드 = 원문 메시지, 프롬프트, 모델 응답, API 키, 베어러 토큰, Discord snowflake 원문,
  전체 업스트림 HTTP body.
- Discord 식별자는 로깅 경계에서 scoped pseudonym/해시로 기록한다(snowflake 직접 기록 금지).

## 불변식

1. participation 결정은 모델 호출 여부와 무관하게 decision log에 남는다(IGNORE도 추적 가능).
2. requestlog는 모델 호출이 실제로 일어난 경우에만 남는다.
3. 두 로그는 correlation ID로만 join되고 원문을 공유하지 않는다.
