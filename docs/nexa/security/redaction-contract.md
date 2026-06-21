# 애플리케이션 로그 redaction 계약 구현 계획

- 작업: NEXA-P02-T012 (security, `human_gate: false`) · 상위: [ADR 0007](../../adr/0007-nexa-social-member-context.md)
- 근거: [logging-baseline.md](./logging-baseline.md)(T017 감사 7건), [logging-boundary.md](../architecture/logging-boundary.md),
  [data-categories.md](../../../specs/product-v2/nexa/data-categories.md),
  [external-model-data.md](../../../specs/product-v2/nexa/external-model-data.md)

## 목적

모든 애플리케이션 로그에서 **금지 필드를 구조적으로 차단**하고 hash/correlation만 허용한다. 금지
필드 목록을 정적 검사 또는 테스트에 연결해 회귀를 막는다.

## 금지 필드 (로그에 절대 기록 금지)

| 필드 | 대체 |
| --- | --- |
| 메시지 원문(raw content) | 없음(기록 안 함) |
| 프롬프트(prompt) | 없음 |
| 모델 응답 본문(response) | 없음 |
| API 키·베어러 토큰 | 없음(인증은 헤더로만) |
| Discord snowflake(userId/guildId/channelId 원문) | scoped pseudonym([ScopedPseudonymizer](../../../central-server/src/main/kotlin/com/discordassistant/central/global/crypto/ScopedPseudonymizer.kt), T010) |
| 전체 업스트림 HTTP body | sanitized error class + status |

## 허용 필드

`status`, `provider`, `requestId`, `correlationId`, scoped pseudonym, sanitized error class, 모델/등급 코드.

## 정적 검사·테스트 연결 (acceptance)

금지 필드 목록을 다음으로 강제한다(구현은 후속 task에서 코드화):

1. **중앙 redactor**: 모든 로그 핸들러(콘솔·파일·WebUI ring handler 포함)에 단일 redaction 필터를
   부착한다. provider-agent `RedactingFilter` 패턴을 central에도 적용하고, WebUI ring handler 누락
   ([logging-baseline.md](./logging-baseline.md) LOG-004)을 메운다.
2. **회귀 테스트**: central `PromptPrivacyTest`·provider `test_privacy.py`를 확장해 금지 필드(원문·
   프롬프트·응답·키·snowflake·HTTP body)가 로그 라인에 나타나지 않음을 sink별로 검증한다.
3. **엔티티 금지 필드 가드**: `AiRequestEntity` 등 로그·영속 모델에 prompt/question/content 필드가
   없음을 테스트로 고정([logging-baseline.md](./logging-baseline.md) SAFE-001 확장).
4. **금지 키 목록 SSOT**: 위 금지 필드명을 상수 목록으로 두고 redactor·테스트가 같은 목록을 참조한다(드리프트 방지).

## 불변식

1. 어떤 로그 sink도 금지 필드를 기록하지 않는다(redactor가 모든 핸들러에 부착됨).
2. Discord 식별자는 로깅 경계에서 scoped pseudonym으로만 남는다(원문 snowflake 금지).
3. 금지 필드 목록은 단일 SSOT이며 redactor와 회귀 테스트가 동일 목록을 강제한다.
