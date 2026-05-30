# ADR 0002: 리버스 터널 에이전트로 유저 로컬 LLM 사용 (BYO-LLM)

- 상태(Status): 제안됨 (Proposed)
- 날짜(Date): 2026-05-30
- 결정자(Deciders): Hyeonjun0527

## 맥락 (Context)

현재 봇은 단일 전역 설정(`OLLAMA_BASE_URL`, 기본 `http://localhost:11434`)으로 **봇 호스트
머신의 Ollama** 에 접속한다(ADR 0001의 `OllamaClient`). 즉 "로컬 LLM" 은 *봇을 운영하는
호스트* 기준의 로컬이며, 디스코드에서 명령을 쓰는 일반 유저의 PC와는 무관하다.

여기서 다음 요구가 생겼다.

- 유저가 **자기 PC의 로컬 LLM(Ollama)** 을 **자기 디스코드 방에서** 그대로 쓰고 싶다.
- 중앙 봇은 하나만 운영하고 싶다(유저마다 봇 토큰을 발급/배포하는 self-host 방식은 피한다).
- 유저 PC는 보통 공인 IP가 없고 NAT/방화벽 뒤에 있어, 봇 호스트가 유저 PC의
  `localhost:11434` 로 **inbound 접속**하는 것은 불가능하다.

### 검토한 대안

| 방안 | 요지 | 평가 |
| --- | --- | --- |
| A. 봇 self-host | 유저가 자기 PC에서 봇 인스턴스를 직접 실행 | 코드 변경 최소이나 유저마다 봇 토큰 발급·배포 부담, 중앙 운영 불가 |
| B. 터널 + 유저별 엔드포인트 | 유저가 ngrok/cloudflare 로 Ollama 를 공개 URL 로 노출, 봇이 유저별 URL 저장·호출 | 봇이 임의 URL 로 나가 **SSRF 위험**, 유저 진입장벽 큼 |
| **C. 리버스 터널 에이전트** | 유저 PC의 경량 에이전트가 중앙 릴레이로 **outbound** 연결을 열어둠. 추론 요청은 그 연결로만 흐름 | **채택.** NAT 통과 + SSRF 원천 차단 |

핵심 원리: **NAT/방화벽은 outbound(안→밖) 연결은 막지 않는다.** 디스코드 봇 자체가 이미
outbound 로 Discord 게이트웨이에 붙는 구조이므로, 같은 원리를 유저 PC ↔ 중앙 릴레이
연결에 적용한다.

## 결정 (Decision)

방안 C(리버스 터널 에이전트)를 채택한다. 세 가지 구성요소를 추가한다.

```
┌─ 유저 PC ───────────────┐          ┌─ 중앙 봇 호스트 ───────────┐
│ Ollama(localhost:11434) │          │  Discord 봇                │
│        ▲                │          │   └ RemoteAgentClient ───┐ │
│   ┌────┴──────┐         │   wss    │  WS 릴레이(aiohttp)      │ │
│   │  에이전트 │──outbound┼─────────▶│  (토큰→guild/user 매핑)  │ │──▶ Discord
│   └───────────┘         │ 연결유지  │                          │ │
└─────────────────────────┘          └───────────────────────────┘
```

### 1. `RemoteAgentClient` (LLM 어댑터)

`src/discord_assistant/llm.py` 에 `BaseLLMClient` 를 구현하는 새 클라이언트를 추가한다.
ADR 0001 의 추상화를 그대로 따르므로, 명령 핸들러(요약·Q&A·번역 등)는 코드 변경 없이
유저 PC의 LLM 을 사용하게 된다.

```python
class RemoteAgentClient(BaseLLMClient):
    async def generate(self, prompt: str, *, model: str | None = None) -> str:
        # 해당 guild/user 에 연결된 에이전트로 추론 요청 프레임을 보내고 응답을 기다린다.
        ...
```

- `LLMProvider` enum 에 `REMOTE_AGENT`(값: `"remote_agent"`) 추가.
- `bot._get_llm(config, settings)` 라우팅에 분기 추가. 연결된 에이전트가 없으면
  `UserFacingError`("LLM 에이전트가 오프라인입니다. PC에서 에이전트를 켜주세요").

### 2. WS 릴레이 (중앙 봇 측)

봇 프로세스 안에서 `aiohttp` 웹서버로 WebSocket 엔드포인트를 띄운다. `aiohttp` 는 이미
discord.py 런타임 의존성이며 `health.py` 가 동일한 방식으로 aiohttp 서버를 운영 중이므로
새 인프라가 필요 없다.

- 에이전트의 연결을 받아 페어링 토큰으로 인증 → `토큰 → (guild_id | user_id)` 매핑 유지.
- in-memory 연결 레지스트리: `{ owner_id: WebSocketConnection }`.
- request_id ↔ 응답 future 상관관계, 요청 타임아웃, heartbeat(ping/pong), 재연결 처리.
- 봇이 **임의 URL 로 나가지 않고** 이미 인증된 연결로만 통신한다(SSRF 불가).

### 3. 유저 에이전트 (유저 PC 측)

별도 경량 프로그램(초기엔 `python -m discord_assistant.agent`, 추후 단일 실행파일/Docker).

- 시작 시 릴레이로 outbound `wss` 연결 후 페어링 토큰으로 인증.
- 추론 요청 프레임 수신 → `localhost:11434` Ollama 호출 → 결과 프레임 회신.
- 끊기면 백오프 재연결. inbound 포트를 전혀 열지 않는다.

### 페어링 흐름

1. 유저가 디스코드에서 `/link` 실행 → 봇이 **일회용·만료성 토큰**을 DM.
2. 유저가 자기 PC에서 `agent --token <TOKEN>` 실행 → 에이전트가 릴레이에 연결·인증.
3. 릴레이가 `토큰 → guild/user` 를 확정. 이후 해당 범위의 LLM 명령이 그 에이전트로 라우팅.
4. `/unlink` 로 해제, 토큰 재발급 지원.

### 메시지 프로토콜 (JSON over wss)

- 요청: `{ "type": "infer", "request_id": "...", "model": "...", "prompt": "..." }`
- 응답: `{ "type": "result", "request_id": "...", "text": "..." }`
- 오류: `{ "type": "error", "request_id": "...", "message": "..." }`
- 제어: `ping` / `pong`(heartbeat), `auth`(연결 직후 토큰 전달).
- 스트리밍(`generate_stream`)은 후속 단계에서 chunk 프레임으로 확장.

### 라우팅 정책 — 두 가지 모드 (길드별 선택)

추론을 어느 에이전트로 보낼지는 **라우팅 키**가 결정한다. 키를 무엇으로 잡느냐만 바꾸면
완전히 다른 두 사용 시나리오가 같은 인프라 위에서 성립한다. 이는 어려운 마법이 아니라
연결 레지스트리 조회 키를 바꾸는 정책 차이다.

| 모드 | 라우팅 키 | 매핑 | 비유 |
| --- | --- | --- | --- |
| **개인 모드 (personal)** | `user_id` | 질문한 사람의 PC 에이전트 | 각자 자기 노트북으로 계산 |
| **서버 공유 모드 (shared)** | `guild_id` | 그 서버에 등록된 **대표(호스트) 에이전트** | 동아리방 고성능 PC 한 대를 모두가 공유 |

```
[개인 모드]  질문자 A → user_id(A) → A PC 에이전트 → A PC Ollama
[공유 모드]  서버 내 누구든 → guild_id → host_agent → 방장 PC Ollama
```

- **개인 모드**: 멤버 각자가 `/link` 로 자기 PC를 연결. 질문자 기준으로 라우팅.
- **공유 모드**: 방장(또는 관리자)만 `/host-llm` 으로 자기 PC를 **서버 대표**로 등록.
  그 서버에서 누가 질문하든 전부 방장 PC Ollama 로 라우팅 → 멤버는 아무것도 설치 불필요.

모드는 길드별 설정(`GuildConfig`)에 저장하고 `/settings` 또는 `/config` 에서 전환한다.
연결 레지스트리는 두 종류의 키를 모두 보관한다: `{ user_id|guild_id → WebSocketConnection }`.

#### 공유 모드 등록 흐름 (`/host-llm`)

1. 방장이 디스코드에서 `/host-llm` 실행 → 봇이 **이 서버의 LLM 호스트 토큰**을 DM.
2. 방장이 자기 PC에서 `agent --token <TOKEN>` 실행.
3. 릴레이가 다음을 저장한다.
   ```
   guild_id        : 해당 서버
   host_user_id    : 방장
   agent_connection: 방장 PC와 연결된 WebSocket
   ```
4. 이후 그 서버의 모든 LLM 명령은 `guild_id → host_agent` 로 라우팅된다.
   `/unhost-llm` 으로 해제.

## 결과 (Consequences)

**장점**

- 유저가 자기 로컬 LLM 을 자기 디스코드 방에서 그대로 사용한다(비전 충족).
- 중앙 봇 하나로 운영 가능. 유저는 봇 토큰 발급이 불필요.
- NAT/방화벽 무관(outbound 만 사용). 유저 PC는 inbound 포트를 열지 않아 공격면이 없다.
- 봇이 임의 URL 로 나가지 않으므로 **SSRF 원천 차단**.
- ADR 0001 의 `BaseLLMClient` 추상화 덕에 명령 핸들러 변경이 거의 없다.

**단점 / 트레이드오프**

- 연결 브로커 상태관리(상관관계·타임아웃·재연결·heartbeat)가 새로 필요하다(중간 난이도).
- 유저 PC가 꺼지면 해당 LLM 명령이 동작하지 않는다(명확한 오프라인 안내 필요).
- 에이전트 배포/온보딩 UX(실행파일·pip·Docker)가 채택 성패를 좌우한다.
- 페어링 토큰 보안(만료·단발성·재발급)과 `wss`(TLS) 강제가 필수.

**공유 모드 고유의 주의점 (반드시 처리)**

- **병목 / 동시성**: 한 서버의 모든 요청이 방장 PC 한 대로 몰린다. 호스트 에이전트당
  **동시 처리 개수 제한 + 대기 큐**가 필요하다(예: 동시 1~2개, 초과분은 큐잉하고
  "대기 중" 표시). 제한이 없으면 방장 PC가 과부하로 멈춘다.
- **단일 장애점**: 방장 PC가 꺼지면 **서버 전체** LLM 이 먹통이 된다. "이 서버의 LLM
  호스트가 오프라인입니다. 방장 PC에서 에이전트를 켜주세요" 안내로 명확히 처리한다.
- **프라이버시(가장 중요)**: 멤버가 입력한 프롬프트가 결국 **방장 PC로 전송**된다. 방장
  에이전트가 로그를 남기면 방장이 타 멤버의 질문을 볼 수 있다. 따라서 공유 모드가 켜진
  서버에서는 명령 응답/`/help` 에 **고지**를 노출해야 한다:
  > "이 서버는 공유 LLM 모드입니다. 질문 내용은 서버 LLM 호스트 PC로 전송됩니다.
  > 민감한 정보는 입력하지 마세요."

## 구현 단계 (제안)

1. **Phase 1 (PoC)**: WS 릴레이 + 최소 에이전트 + `RemoteAgentClient` + `/link`.
   단일 유저·단일 모델로 `/ask` end-to-end 성공.
2. **Phase 2**: 재연결/heartbeat/타임아웃/오프라인 안내, per-guild 라우팅, `/unlink`.
3. **Phase 3**: 에이전트 패키징(실행파일/Docker), `/settings` 통합, 다중 동시 연결,
   스트리밍 응답.

## 미해결 질문 (Open Questions)

- ~~라우팅을 per-guild 로 고정할지, per-user 도 허용할지~~ → **해결**: 길드별 설정으로
  개인/공유 두 모드를 모두 지원한다(위 "라우팅 정책" 참조).
- 공유 모드 호스트 에이전트당 **동시 처리 개수 기본값**(1 vs 2)과 큐 길이 상한.
- 프라이버시 고지를 **매 응답에 노출**할지, **최초 1회/주기적**으로만 노출할지(UX vs 경각심).
- 페어링/호스트 토큰 저장 위치 및 수명(메모리 vs SQLite, `SECRET_KEY` 연동 여부).
- 한 길드에 여러 에이전트가 붙을 때의 선택/페일오버 정책(공유 모드는 단일 호스트가 기본).
- 에이전트 인증을 토큰 단발성 외에 상호 TLS 등으로 강화할지.
