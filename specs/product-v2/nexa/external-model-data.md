# 외부 GLM 전송 최소화 계약

- 작업: NEXA-P02-T006 (`human_gate: true`, security) · 상위: [ADR 0006](../../../docs/adr/0006-central-cloud-llm-backend.md)
- 근거: [data-categories.md](./data-categories.md), [speech-context.md](../../../docs/nexa/architecture/speech-context.md),
  [logging-boundary.md](../../../docs/nexa/architecture/logging-boundary.md)

## 결정

speech가 routing CloudLlm(GLM-5.1, z.ai)에 보내는 payload는 **최소 필드 원칙**을 따른다. 보낼 수
있는 것과 절대 보내지 않는 것을 필드 단위로 못 박는다.

### 전송 허용 / 금지

| 필드 | 전송 | 처리 |
| --- | --- | --- |
| 발화에 필요한 장면 텍스트 | 허용(최소) | 옵트아웃 사용자([user-opt-out.md](./user-opt-out.md)) 메시지 제외 |
| 관계/기억 요약 | 허용(요약본) | 원문 일화가 아니라 요약·플래그만 |
| 첨부(이미지/파일) | 기본 미전송 | 필요 시 별도 검토. 원본 바이트 전송 금지 |
| 작성자 식별자 | **금지** | 가명조차 모델에 불필요하면 미포함([pseudonymization 규칙](./data-categories.md), T010) |
| API 키·내부 ID·correlation ID | **금지** | 인증 헤더로만, 본문 금지 |
| 시스템 내부 메타데이터 | **금지** | 프롬프트 본문에 넣지 않음 |

## acceptance 충족

- **payload를 필드 단위로 감사할 수 있다**: 전송 직전 payload 구성이 위 표의 허용 필드로만 이뤄지며
  구조화된 형태라 필드별 검사가 가능하다.
- **API 키·내부 ID는 포함되지 않는다**: 인증은 Authorization 헤더로만, 본문에 키·내부 ID 없음.

## 불변식

1. speech payload는 허용 목록(allow-list) 필드로만 구성된다(deny by default).
2. 작성자 식별자·API 키·내부 ID·correlation ID는 모델 본문에 절대 들어가지 않는다.
3. 옵트아웃 사용자 데이터는 payload에 포함되지 않는다.
