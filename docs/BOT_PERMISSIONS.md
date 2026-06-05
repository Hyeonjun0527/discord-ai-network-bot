# NEXA Discord 봇 권한 명세

이 문서는 NEXA를 Discord 서버에 초대하거나 운영할 때 필요한 **서버 권한**, **OAuth2 scope**, **Privileged Gateway Intent** 를 명확히 정의한다.

핵심 원칙:

- `관리자(Administrator)` 권한은 기본 요구사항이 아니다.
- `/질문`, `/도움말`, 버튼, 모달 같은 **슬래시/인터랙션 기능은 Message Content Intent 없이도 동작**해야 한다.
- `@니아 질문`처럼 **일반 메시지 본문을 읽는 멘션 호출은 Message Content Intent 가 필요**하다.
- 권한/Intent 누락은 봇 전체 장애처럼 보이면 안 된다. 가능한 한 관리자에게 무엇을 켜야 하는지 알려줘야 한다.

## 1. OAuth2 scopes

초대 URL에는 아래 scope 를 포함한다.

| Scope | 필수 여부 | 이유 |
|---|---:|---|
| `bot` | 필수 | 봇 유저를 서버에 추가한다. |
| `applications.commands` | 필수 | `/질문`, `/도움말`, `/메뉴` 등 슬래시 명령어와 컨텍스트 메뉴를 등록/사용한다. |

불필요한 scope:

- `identify`, `guilds`, `email` 등 사용자 OAuth 로그인 scope 는 봇 초대에는 필요 없다.

## 2. 서버 권한 체크리스트

### 2.1 최소 권한

슬래시 명령/버튼/모달을 기본 봇 이름으로만 쓰는 최소 권한이다.

| Discord 권한 | 필수 여부 | 필요한 기능 |
|---|---:|---|
| 채널 보기 / View Channel | 필수 | 봇이 대상 채널을 볼 수 있어야 한다. |
| 메시지 보내기 / Send Messages | 필수 | 일반 답변, 오류 안내, 도움말 메시지를 보낸다. |
| 링크 임베드 / Embed Links | 권장 | 메뉴/도움말/상태 임베드를 보기 좋게 표시한다. |
| 메시지 기록 보기 / Read Message History | 권장 | 컨텍스트 메뉴, 답변 thread/context UX, 일부 Discord 표시 안정성에 필요하다. |
| 슬래시 명령어 사용 / Use Application Commands | 권장 | 서버/채널 권한 정책에서 앱 명령 사용을 막지 않기 위해 허용한다. |

최소 권장 Permissions Integer:

```text
2147568640
```

포함 비트:

```text
View Channel + Send Messages + Embed Links + Read Message History + Use Application Commands
```

### 2.2 권장 권한

NEXA의 현재 제품 UX를 제대로 쓰기 위한 권장 권한이다.

| Discord 권한 | 필수 여부 | 필요한 기능 |
|---|---:|---|
| 채널 보기 / View Channel | 필수 | 모든 채널 기능의 기본. |
| 메시지 보내기 / Send Messages | 필수 | 답변/안내 전송. |
| 링크 임베드 / Embed Links | 권장 | 메뉴/도움말/상태 카드. |
| 파일 첨부 / Attach Files | 선택 | 향후 이미지/로그/리포트 첨부형 UX. 현재 핵심 필수는 아님. |
| 메시지 기록 보기 / Read Message History | 권장 | 컨텍스트 메뉴/멘션 주변 UX 안정성. |
| 반응 추가 / Add Reactions | 선택 | 멘션 답변 성공 표시, 만족도/상태 리액션 UX. |
| 외부 이모지 사용 / Use External Emojis | 선택 | 커스텀 이모지/브랜드 이모지 UX를 쓸 경우. |
| 웹후크 관리 / Manage Webhooks | 강력 권장 | 채널별 AI 이름/프로필 아이콘으로 답변을 보내는 Channel AI Webhook 표시. 없으면 일반 봇 응답으로 폴백해야 한다. |
| 슬래시 명령어 사용 / Use Application Commands | 권장 | 앱 명령 사용 가능 상태 유지. |

권장 Permissions Integer:

```text
2684734528
```

포함 비트:

```text
Add Reactions + View Channel + Send Messages + Embed Links + Attach Files + Read Message History + Use External Emojis + Manage Webhooks + Use Application Commands
```

### 2.3 채널 AI 프로필 표시 권한

채널별 AI가 `코드 니아`, `번역 니아`처럼 **그 채널만의 이름/아이콘으로 답변**하려면 다음 권한이 필요하다.

```text
Manage Webhooks
```

없을 때 기대 UX:

1. 질문 처리는 계속한다.
2. Webhook 전송에 실패하면 일반 봇 메시지로 폴백한다.
3. 관리자에게 “채널 AI 이름/아이콘 표시에는 웹후크 관리 권한이 필요합니다”를 안내한다.
4. Provider 처리 결과 자체를 버리면 안 된다.

## 3. Privileged Gateway Intents

Discord Developer Portal → Application → Bot → Privileged Gateway Intents 에서 설정한다.

| Intent | 필수 여부 | 필요한 기능 | 꺼져 있을 때 |
|---|---:|---|---|
| Message Content Intent | `@니아 질문`에 필수 | 일반 메시지 본문을 읽어 멘션 질문으로 처리한다. | JDA가 이 intent 를 요청하면 Discord가 `4014 DISALLOWED_INTENTS` 로 gateway 연결을 끊을 수 있다. |
| Server Members Intent | 현재 불필요 | 멤버 전체 목록/상세 동기화가 필요할 때만. | 현재 켜지 않는다. |
| Presence Intent | 현재 불필요 | 멤버 온라인 상태 기반 기능이 필요할 때만. | 현재 켜지 않는다. |

중요:

- `/질문`, `/도움말`, `/메뉴`, 버튼, 모달, 컨텍스트 메뉴는 Message Content Intent 없이도 설계상 동작 가능해야 한다.
- 하지만 `@니아 질문`을 제품 기능으로 제공하려면 Message Content Intent 를 켜야 한다.
- `DISCORD_MESSAGE_CONTENT_INTENT_ENABLED=false` 로 배포하면 봇은 MESSAGE_CONTENT 를 요청하지 않고 슬래시 명령만 안전하게 부팅한다.
- `DISCORD_FALLBACK_WITHOUT_MESSAGE_CONTENT_ON_4014=true` 이면 Developer Portal 설정이 꺼져 `4014 DISALLOWED_INTENTS` 가 발생해도 @멘션 질문을 끄고 슬래시 명령만 자동 재기동한다.

## 4. Developer Portal 설정 순서

1. Discord Developer Portal → Applications → NEXA 앱 선택.
2. Installation 또는 OAuth2 URL Generator 에서 scope 선택:
   - `bot`
   - `applications.commands`
3. Bot Permissions 에서 권장 권한 선택:
   - View Channel
   - Send Messages
   - Embed Links
   - Read Message History
   - Use Application Commands
   - Manage Webhooks
   - Add Reactions
   - 필요 시 Attach Files, Use External Emojis
4. `@니아 질문`을 지원하려면 Bot → Privileged Gateway Intents:
   - Message Content Intent ON
5. 변경 후 봇을 재시작/재배포한다.
6. 운영 로그에서 아래를 확인한다.

```text
JDA - Login Successful
Discord(JDA) 기동 완료
```

아래 로그가 나오면 Message Content Intent 설정 문제다.

```text
`CloseCode[DISALLOWED_INTENTS] code=4014`
```

## 5. 장애/UX 처리 원칙

권한/Intent 문제는 사용자가 봤을 때 “봇이 멍청하게 죽었다”가 아니라 “무엇을 켜야 하는지 알려준다”가 되어야 한다.

| 상황 | 사용자/관리자 안내 원칙 |
|---|---|
| Message Content Intent 꺼짐 | `@호출을 사용하려면 Developer Portal에서 Message Content Intent를 켜야 합니다.` |
| Manage Webhooks 없음 | `채널 AI 이름/아이콘 표시에는 웹후크 관리 권한이 필요합니다. 답변은 기본 봇 이름으로 보냅니다.` |
| Send Messages 없음 | 가능한 경우 ephemeral interaction 으로 `이 채널에 메시지 보내기 권한이 없습니다.` |
| Embed Links 없음 | plain text 도움말로 폴백. |
| 채널 보기 없음 | 해당 채널 기능을 비활성/경고로 처리. |
| Discord API rate limit | 잠시 후 재시도 안내 + 내부 재시도/backoff. |
| Gateway intent 거부 | 운영 알림/로그에 치명으로 남기고, 배포 후 smoke test에서 잡는다. |

## 6. 공식 문서 참고

- Discord Permissions: https://docs.discord.com/developers/topics/permissions
- Discord Application Commands: https://docs.discord.com/developers/interactions/application-commands
- Discord Receiving and Responding to Interactions: https://docs.discord.com/developers/interactions/receiving-and-responding
- Discord Message Content Intent FAQ: https://support-dev.discord.com/hc/en-us/articles/4404772028055-Message-Content-Intent-FAQ-Redirecting
