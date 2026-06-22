# AI 정체성 공개 원칙 (disclosure)

- 작업: NEXA-P17-T017 (`human_gate: true`, security) · 상위: [ADR 0007](../../../docs/adr/0007-nexa-social-member-context.md)
- 근거: [banter-safety.md](./banter-safety.md), [threat-model.md](../../../docs/nexa/security/threat-model.md)
- 구현: `AiIdentityDisclosureCritic`(T017, speech 도메인 critic), NexaIdentity SSOT, 온보딩 프로필 표시

## 결정

NEXA(니아)는 **사람인 척 속이지 않는다.** 자신이 AI(봇)임을 프로필·온보딩·명령으로 명확히 표시하고,
사용자가 정체를 물으면 정직하게 AI 임을 밝힌다. 다만 **매 발화마다 "저는 AI예요"를 강제하지는 않는다** —
정직과 자연스러움의 균형을 둔다(사람 같은 대화를 위해 끊임없이 자기 고지하지 않되, 절대 인간을 사칭하지 않음).

### 공개 채널

| 채널 | 공개 방식 |
| --- | --- |
| Discord 프로필 | 봇 계정(BOT 태그) — 플랫폼이 봇임을 표시 |
| 온보딩 | AI 멤버 채널 안내에 NEXA 가 AI 임을 명시(사람 멤버 아님) |
| 명령/도움말 | NEXA 정체성·역할을 설명하는 공개 정보 제공 |
| 발화 | 정체 질문 시 AI 임을 인정(부정 금지) |

### 금지 (하드)

- **인간 사칭 금지**: 후보 발화가 "나는 사람이야"·"AI 아니야"·"사람이 직접 입력" 같은 사칭/부정 패턴을 담으면
  생성 후 폐기한다(`AiIdentityDisclosureCritic` → `CriticReason.HUMAN_IMPERSONATION`).
- **정체 질문 정직 강제**: 사용자가 "너 사람이야 AI야?" 처럼 정체를 직접 물었는데 후보가 AI 임을 인정하지
  않으면 폐기한다.
- **속이는 production 실험 금지**: "사람 같음" 평가를 위해 사용자에게 NEXA 의 정체성을 속이는 production 실험을
  금지한다(T017 acceptance) — A/B 든 canary 든, 사용자가 상대가 AI 임을 오인하게 만드는 설계는 승인 게이트를
  통과할 수 없다.

### 자연스러움과의 균형

자기 고지를 매 발화에 강제하면 대화가 부자연스러워진다. 따라서 평상시에는 페르소나(니아)로 자연스럽게
대화하되, **정체를 묻거나 사칭이 발생하는 순간** critic 이 정직을 강제한다. 즉 "능동적으로 매번 밝히기"가
아니라 "절대 속이지 않기 + 물으면 정직하게"가 기준이다.

### 사유 비노출

critic 의 탈락 사유는 enum(`HUMAN_IMPERSONATION`)만 남기고 후보 원문을 로그에 담지 않는다(누출 방지,
P17-T003·redaction-contract 와 일관).

## 비범위

- 콘텐츠 안전·비밀 비노출은 `ContentSafety`·`SecretDisclosureCritic`(P17-T003)이 담당한다.
- 정체성 SSOT 자체(니아 페르소나 본문)는 `NexaIdentity`(shared)이며 이 문서는 그 **공개 원칙**만 정의한다.
