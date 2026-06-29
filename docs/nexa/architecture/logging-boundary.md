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

## Raw context와 tombstone

`RawContextStore`는 requestlog/decision log가 아니다. 단일 judge에 넣는 원문 transcript만 별도로
암호화·보존 한도·동의 경계를 적용해 관리한다.

- live raw row는 context window의 유일한 원문 출처다. 메시지 삭제, 동의 철회, 채널/길드 disable,
  FIFO eviction이 발생하면 해당 row는 즉시 제거되어 다음 judge/speech 입력에 들어가지 않는다.
- 삭제/eviction 후 순서와 존재 증거가 필요할 수 있으므로 `nexa_raw_context_tombstone`만 남긴다.
  tombstone은 scope/message fingerprint, 발생/삭제 시각, reason, source type, content length만 보존한다.
- tombstone에는 원문, prompt, response, Discord snowflake, author pseudonym, reply target id를 저장하지 않는다.
- 파생 memory와 dataset export는 source event redaction/cascade 및 export boundary에서 다시 무효화·제외한다.
  tombstone은 재export/삭제 감사 증거일 뿐 학습 입력이 아니다.

## 불변식

1. participation 결정은 모델 호출 여부와 무관하게 decision log에 남는다(IGNORE도 추적 가능).
2. requestlog는 모델 호출이 실제로 일어난 경우에만 남는다.
3. 두 로그는 correlation ID로만 join되고 원문을 공유하지 않는다.
4. raw context tombstone은 원문 복구 수단이 아니며, live context window와 dataset export 입력으로 사용하지 않는다.
