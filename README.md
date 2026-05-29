# Discord AI Assistant MVP

로컬 LLM(Ollama)로 디스코드 채널의 최근 대화를 요약하고, 최근 맥락 기반 질문에 답하는 졸업프로젝트용 MVP입니다.

## 구현 범위

- `/summarize`, `/pin-summary`, `/summarize-channels`: 채널 요약 (단일/고정/멀티 채널)
- `/ask`: 최근 채널 맥락 기반 질의응답
- `/chat`: 채널 맥락 없는 자유 대화
- `/translate`: 짧은 텍스트 번역
- `/search`: 키워드 검색 + 요약
- `/export`: 채널 메시지 마크다운 내보내기
- `/remind`, `/stats`, `/help`: 알림 · 통계 · 도움말
- `/settings`, `/config ...`: 대화형 패널 및 서버별 설정 저장
- 멀티 프로바이더 LLM: Ollama(로컬), OpenAI, Anthropic
- SQLite 저장소: 서버 설정과 명령 사용 로그
- 멘션 기반 호출: 봇을 멘션하면 요약, 멘션 뒤 질문을 쓰면 Q&A

## 빠른 시작

```bash
python3 -m venv .venv
source .venv/bin/activate
pip install -e ".[dev]"
cp .env.example .env
```

`.env`에 `DISCORD_BOT_TOKEN`을 입력합니다. 토큰 값은 커밋하거나 공유하지 마세요.

Ollama를 실행하고 모델을 준비합니다.

```bash
ollama serve
ollama pull llama3.1:8b
```

봇 실행:

```bash
discord-assistant
# 또는
python -m discord_assistant
```

## Discord 봇 설정

Discord Developer Portal에서 Bot을 만들고 다음 권한/인텐트를 확인합니다.

- Privileged Gateway Intents: **Message Content Intent** 활성화
- OAuth2 URL Generator scopes: `bot`, `applications.commands`
- Bot permissions: `Read Message History`, `Send Messages`, `Use Slash Commands`, `View Channels`

## 환경변수

| 이름 | 기본값 | 설명 |
| --- | --- | --- |
| `DISCORD_BOT_TOKEN` | 없음 | Discord 봇 토큰 |
| `OLLAMA_BASE_URL` | `http://localhost:11434` | Ollama 서버 주소 |
| `OLLAMA_MODEL` | `llama3.1:8b` | 기본 LLM 모델명 |
| `DATABASE_URL` | `sqlite:///./data/discord_assistant.db` | SQLite DB URL |
| `DEFAULT_SUMMARY_LIMIT` | `50` | 기본 메시지 조회 개수 |
| `MAX_CONTEXT_CHARS` | `12000` | 프롬프트에 넣을 최대 대화 길이 |
| `DEFAULT_LANGUAGE` | `ko` | 기본 응답 언어 |
| `OLLAMA_TIMEOUT_SECONDS` | `60` | Ollama 요청 타임아웃 |

## 명령어

> 아래 표는 `src/discord_assistant/bot.py`에 실제 등록된 모든 슬래시 명령과 동기화되어 있습니다.

### 일반 명령

| 명령어 | 설명 |
| --- | --- |
| `/summarize [limit:선택] [since:선택]` | 최근 메시지를 핵심 주제, 결정사항, 액션 아이템 중심으로 요약합니다. `since`는 시간 필터(예: `1h`, `30m`, `2d`). |
| `/ask question [limit:선택]` | 최근 메시지 안에서 근거를 찾아 답합니다. |
| `/chat message [public:선택]` | 채널 맥락 없이 AI에게 자유롭게 질문합니다. `public:true`면 공개 메시지로 표시. |
| `/translate text [target_language:선택]` | 텍스트를 지정 언어로 번역합니다. 기본 `ko`. |
| `/search query [limit:선택]` | 채널에서 키워드로 메시지를 검색하고 요약합니다. 기본 검색 범위 200개. |
| `/remind minutes` | 마지막 `/summarize` 결과를 N분(1~60) 후 DM으로 전송합니다. |
| `/pin-summary [limit:선택]` | 요약을 실행하고 결과를 채널에 고정합니다. 메시지 관리 권한 필요. |
| `/summarize-channels` | 여러 채널을 선택해 통합 요약합니다. (서버 전용) |
| `/export [limit:선택]` | 채널 메시지를 마크다운 파일로 내보내 DM으로 전송합니다. |
| `/stats` | 서버 봇 사용 통계를 표시합니다. (서버 전용) |
| `/help` | 모든 명령어 사용법을 안내합니다. |
| `/settings` | 대화형 설정 패널을 엽니다. 관리자 전용. |

### `/config` 하위 명령 (관리자 전용)

서버 설정을 변경하려면 `Manage Server` 또는 관리자 권한, 혹은 `/config admin_role`로 지정한 역할이 필요합니다.

| 명령어 | 설명 |
| --- | --- |
| `/config model model` | 서버 기본 모델명을 저장합니다. 예: `llama3.1:8b`, `qwen2.5:7b`, `gemma2:9b`. |
| `/config summary_limit limit` | 기본 요약 범위를 1~200개 사이로 저장합니다. |
| `/config language language` | 기본 응답 언어를 저장합니다. 예: `ko`, `en`, `ja`, `auto`. |
| `/config admin_role role` | 봇 설정 권한을 가진 역할을 지정합니다. |
| `/config persona [description:선택]` | `/chat` 페르소나를 설정합니다. 비워두면 초기화. |
| `/config auto_summary interval` | 자동 요약 간격(분, 최소 5)을 설정합니다. `0`이면 비활성화. |
| `/config custom_prompt prompt_type text` | `summarize`/`ask` 커스텀 프롬프트를 설정합니다. `text`가 비면 초기화. |
| `/config allowed_role [role:선택]` | 명령어를 사용할 수 있는 역할을 제한합니다. 비워두면 제한 해제. |

### /chat 예시

```
/chat message:파이썬 리스트 컴프리헨션 설명해줘
/chat message:영어 이메일 초안 작성해줘
```

### /settings 패널

`/settings` 명령으로 인터랙티브 설정 패널을 열 수 있습니다.

- **제공자 변경**: Ollama(로컬) / OpenAI(GPT) / Anthropic(Claude) 전환
- **모델 관리**: 모델 선택, Ollama 모델 설치, OpenAI·Anthropic 모델 변경
- **일반 설정**: 응답 언어, 요약 범위 조정

### /help 예시

```
/help
```

봇의 모든 명령어 목록과 사용 예시를 ephemeral 메시지로 안내합니다.

## 개발 일정 기준

- 2026-05-28 ~ 6월 1주: 프로젝트 초기화, Discord/Ollama 연결, 기본 slash command
- 6월 2~3주: `/summarize`, `/ask` MVP, 프롬프트/오류 처리
- 6월 4주 ~ 7월 2주: 서버별 설정, SQLite 저장, 품질/응답 시간 개선
- 7월 3~4주: 테스트 채널/서버 베타 테스트와 피드백 수집
- 8월: 결과 분석, 발표 자료, 데모 시나리오 정리

## 테스트

외부 서비스 없이 핵심 모듈 단위 테스트를 실행할 수 있습니다.

```bash
PYTHONPATH=src python3 -m unittest discover -s tests
```

봇 통합 테스트는 실제 Discord 테스트 서버와 로컬 Ollama가 필요합니다.
