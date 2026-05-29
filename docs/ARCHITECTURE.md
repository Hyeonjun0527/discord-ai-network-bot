# Discord AI Assistant — 아키텍처 문서
# Architecture Documentation

---

## 1. 시스템 아키텍처 다이어그램

```
┌─────────────────────────────────────────────────────────────────────────┐
│                          Discord Platform                                │
│                                                                          │
│   ┌──────────────┐      Slash Commands       ┌──────────────────────┐  │
│   │ Discord User │ ────────────────────────► │   Discord API        │  │
│   │  (Client)    │ ◄────────────────────────  │   (Gateway / REST)  │  │
│   └──────────────┘      Bot Responses         └──────────┬───────────┘  │
│                                                           │              │
└───────────────────────────────────────────────────────────┼─────────────┘
                                                            │ WebSocket / HTTP
                                                            ▼
┌──────────────────────────────────────────────────────────────────────────┐
│                         Discord AI Assistant Bot                          │
│                                                                           │
│   ┌──────────────────────────────────────────────────────────────────┐  │
│   │                      bot.py  (discord.py 2.7.1)                  │  │
│   │                                                                    │  │
│   │  ┌───────────┐  ┌───────────┐  ┌──────────┐  ┌───────────────┐  │  │
│   │  │/summarize │  │  /ask     │  │  /chat   │  │  /translate   │  │  │
│   │  │ Command   │  │ Command   │  │ Command  │  │   Command     │  │  │
│   │  └─────┬─────┘  └─────┬─────┘  └─────┬────┘  └───────┬───────┘  │  │
│   │        │               │               │               │           │  │
│   │        └───────────────┴───────────────┴───────────────┘           │  │
│   │                                │                                     │  │
│   │                    ┌───────────▼────────────┐                       │  │
│   │                    │     LLM Router         │                       │  │
│   │                    │     (llm.py)           │                       │  │
│   │                    └───────┬────────┬───────┘                       │  │
│   │                            │        │                                │  │
│   │               ┌────────────┘        └──────────────┐               │  │
│   │               │                                      │               │  │
│   │  ┌────────────▼─────┐  ┌──────────────┐  ┌─────────▼──────────┐  │  │
│   │  │  Ollama Client   │  │OpenAI Client │  │ Anthropic Client   │  │  │
│   │  │  (HTTP/11434)    │  │   (REST API) │  │   (REST API)       │  │  │
│   │  └────────┬─────────┘  └──────┬───────┘  └──────────┬─────────┘  │  │
│   └───────────┼────────────────────┼──────────────────────┼────────────┘  │
│               │                    │                       │               │
│   ┌───────────▼──────────────────────────────────────────▼────────┐      │
│   │                    Storage Layer (storage.py)                   │      │
│   │   ┌─────────────────────────────────────────────────────────┐  │      │
│   │   │               SQLite Database (aiosqlite)               │  │      │
│   │   │                                                          │  │      │
│   │   │  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐  │  │      │
│   │   │  │ server_config│  │ command_logs │  │  api_keys    │  │  │      │
│   │   │  │   (settings) │  │  (usage log) │  │  (encrypted) │  │  │      │
│   │   │  └──────────────┘  └──────────────┘  └──────────────┘  │  │      │
│   │   └─────────────────────────────────────────────────────────┘  │      │
│   └────────────────────────────────────────────────────────────────┘      │
└───────────────────────────────────────────────────────────────────────────┘
         │                           │                        │
         ▼                           ▼                        ▼
┌────────────────┐     ┌─────────────────────┐   ┌──────────────────────┐
│  Ollama Server │     │    OpenAI API        │   │   Anthropic API      │
│  (localhost    │     │  (api.openai.com)    │   │  (api.anthropic.com) │
│   :11434)      │     │                      │   │                      │
│                │     │  gpt-4o-mini         │   │  claude-haiku-4-5    │
│  llama3.2      │     │  gpt-4o              │   │  claude-sonnet-4-6   │
│  mistral       │     │                      │   │                      │
│  gemma2        │     └─────────────────────┘   └──────────────────────┘
│  phi3          │
└────────────────┘

                    Optional: Web Dashboard
┌──────────────────────────────────────────────────────────┐
│                    Dashboard Layer                         │
│                                                            │
│   ┌──────────────────┐      ┌───────────────────────┐    │
│   │  Next.js         │      │  FastAPI Backend       │    │
│   │  (Frontend)      │◄────►│  (REST API)            │    │
│   │  localhost:3000  │      │  localhost:8000        │    │
│   └──────────────────┘      └───────────┬────────────┘    │
│                                          │                  │
│                              ┌───────────▼────────────┐    │
│                              │  Discord OAuth2         │    │
│                              │  (Authentication)       │    │
│                              └────────────────────────┘    │
│                                          │                  │
│                              ┌───────────▼────────────┐    │
│                              │     SQLite DB           │    │
│                              │  (Shared with Bot)      │    │
│                              └────────────────────────┘    │
└──────────────────────────────────────────────────────────┘
```

---

## 2. 컴포넌트 설명

### 2.1 Discord Bot Core (`src/discord_assistant/`)

| 파일 | 역할 |
|------|------|
| `bot.py` | 슬래시 커맨드 등록, 이벤트 핸들러, 봇 진입점 |
| `llm.py` | LLM 제공자 추상화 레이어 (Ollama / OpenAI / Anthropic 라우팅) |
| `storage.py` | SQLite CRUD 작업, 서버 설정 관리, 커맨드 로그 |
| `crypto.py` | Fernet 기반 API 키 암호화/복호화 |
| `context.py` | 채널 메시지 수집 및 컨텍스트 빌딩 |
| `prompts.py` | 기능별 프롬프트 템플릿 (요약/Q&A/번역) |
| `ui.py` | Discord Embed, View, 버튼 컴포넌트 정의 |
| `settings.py` | `/settings` 인터랙티브 패널 로직 |
| `cache.py` | 인메모리 응답 캐시 (TTL 기반) |
| `models.py` | 데이터 모델(Pydantic), 설정 스키마 |

---

### 2.2 LLM 라우팅 구조

```
사용자 명령
    │
    ▼
LLM Router (llm.py)
    │
    ├── provider == "ollama"     → OllamaClient
    │                                └── POST http://localhost:11434/api/generate
    │
    ├── provider == "openai"     → OpenAIClient
    │                                └── openai SDK → api.openai.com
    │
    └── provider == "anthropic"  → AnthropicClient
                                     └── anthropic SDK → api.anthropic.com
```

각 클라이언트는 동일한 인터페이스(`generate(prompt: str) -> str`)를 구현하여 교체 가능합니다.

---

### 2.3 데이터베이스 스키마

```sql
-- 서버별 설정
CREATE TABLE server_config (
    guild_id      TEXT PRIMARY KEY,
    provider      TEXT DEFAULT 'ollama',     -- ollama | openai | anthropic
    model         TEXT DEFAULT 'llama3.2',
    summary_limit INTEGER DEFAULT 50,
    language      TEXT DEFAULT 'ko',
    updated_at    TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 암호화된 API 키
CREATE TABLE api_keys (
    guild_id      TEXT,
    provider      TEXT,                       -- openai | anthropic
    encrypted_key TEXT,                       -- Fernet 암호화
    created_at    TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (guild_id, provider)
);

-- 명령어 사용 로그
CREATE TABLE command_logs (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    guild_id      TEXT,
    user_id       TEXT,
    command       TEXT,
    provider      TEXT,
    model         TEXT,
    response_ms   INTEGER,
    created_at    TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
```

---

### 2.4 보안 구조

```
API Key 저장 흐름:

사용자 입력 (API Key 문자열)
        │
        ▼
   crypto.py
   Fernet.encrypt(key, ENCRYPTION_KEY)
        │
        ▼
   SQLite api_keys 테이블 (암호화된 바이트)

API Key 사용 흐름:

   SQLite api_keys 테이블
        │
        ▼
   Fernet.decrypt(encrypted_key, ENCRYPTION_KEY)
        │
        ▼
   LLM Client 초기화 (메모리 내 사용 후 즉시 폐기)
```

`ENCRYPTION_KEY`는 환경 변수에서만 로드하며, 절대 DB나 코드에 저장하지 않습니다.

---

## 3. 데이터 흐름 설명

### 3.1 `/summarize` 흐름

```
1. 사용자가 /summarize limit:30 입력
2. Discord API가 Interaction을 봇에 전달
3. bot.py → context.py: 채널 메시지 최근 30개 수집
4. context.py → prompts.py: 요약 프롬프트 생성
5. prompts.py → llm.py: LLM 호출 (설정된 제공자)
6. LLM 응답 수신 → ui.py: Discord Embed 포맷팅
7. Embed를 Discord API로 전송 (ephemeral 또는 public)
8. storage.py: 명령어 사용 로그 저장
```

### 3.2 `/settings` 제공자 변경 흐름

```
1. 사용자가 /settings 입력 (관리자 권한 필요)
2. ui.py: 설정 패널 Embed + 버튼 View 생성
3. 사용자가 "Anthropic" 버튼 클릭
4. Interaction Callback → API 키 입력 모달 표시
5. 사용자 API 키 입력 → crypto.py: 암호화
6. storage.py: server_config.provider = 'anthropic' 업데이트
7. storage.py: api_keys 테이블에 암호화 키 저장
8. 완료 메시지 전송
```

---

## 4. 기술 스택 표

| 계층 | 기술 | 버전 | 역할 |
|------|------|------|------|
| Discord 연동 | discord.py | 2.7.1 | 슬래시 커맨드, 이벤트 처리 |
| 런타임 | Python | 3.11 | 봇 실행 환경 |
| 데이터베이스 | SQLite + aiosqlite | 3.x | 서버 설정, 로그, API 키 저장 |
| 암호화 | cryptography (Fernet) | 42.x | API 키 대칭키 암호화 |
| 로컬 LLM | Ollama | 0.3.x | 로컬 모델 HTTP API 서버 |
| 클라우드 LLM | openai SDK | 1.x | GPT-4o 계열 API |
| 클라우드 LLM | anthropic SDK | 0.x | Claude 계열 API |
| HTTP 클라이언트 | aiohttp / httpx | — | 비동기 HTTP 요청 |
| 대시보드 (선택) | FastAPI | 0.111.x | REST API 백엔드 |
| 대시보드 (선택) | Next.js | 14.x | 웹 프론트엔드 |
| 인증 (선택) | Discord OAuth2 | — | 대시보드 로그인 |
| 컨테이너 (선택) | Docker + Compose | — | 배포 환경 |

---

## 5. 배포 구성

### 로컬 개발 환경

```
Host Machine
├── python -m discord_assistant   (봇 프로세스)
├── ollama serve                  (로컬 LLM 서버, :11434)
├── uvicorn app:app               (FastAPI 대시보드 선택, :8000)
└── npm run dev                   (Next.js 선택, :3000)
```

### Docker 배포 환경

```
docker-compose.yml
├── discord-bot  (Python 봇 컨테이너)
├── ollama       (Ollama 컨테이너, GPU 선택)
└── dashboard    (FastAPI + Next.js 컨테이너, 선택)

공유 볼륨: ./data → /app/data (SQLite 영속성)
```

---

## 6. 확장성 고려 사항

- **수평 확장**: Discord 샤딩(sharding) 적용 시 대규모 서버 지원 가능
- **제공자 추가**: `BaseLLMClient` 인터페이스 구현으로 새 AI 제공자 플러그인 방식 추가
- **캐시 확장**: Redis로 교체 시 다중 인스턴스 캐시 공유 가능
- **데이터베이스 확장**: PostgreSQL 전환 시 고가용성 구성 가능
