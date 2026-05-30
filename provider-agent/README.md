# Provider Agent (Python)

커뮤니티 멤버(프로바이더)가 **자기 PC에서 실행**하는 경량 클라이언트. 중앙 서버
([central-server](../central-server))에 outbound WebSocket 으로 붙어, 다른 유저의 `/ask` 요청을
**내 PC의 localhost Ollama** 로 처리한다.

설계: [ADR 0002](../docs/adr/0002-remote-agent-byollm.md)/[0003](../docs/adr/0003-community-provider-pool.md),
WS 계약: `specs/.../api.md §8`(중앙 서버와 동일 와이어).

## 설치 / 실행
```bash
cd provider-agent
pip install -e ".[dev]"            # 개발
# 또는 배포물(차수 9): 단일 실행파일

# Discord 에서 /provider-join → 토큰 받기 → 실행:
discord-ai-provider-agent --token ABC-DEF-GHI --relay-url ws://<서버>:8080/agent --model llama3.1:8b
# 또는 환경변수: AGENT_TOKEN, RELAY_URL, OLLAMA_BASE_URL
```

## 동작
```
[내 PC] Ollama(localhost:11434) ← 에이전트 ──outbound wss──▶ [중앙 서버] ──▶ Discord
```
- inbound 포트를 열지 않는다(outbound only). Ollama 포트를 외부에 공개하지 않는다.
- 토큰은 일회용. 프롬프트 내용은 로그에 남기지 않는다.

## 경량 의존성
`aiohttp` 만 필수(봇 의존성 없음). 자원 모니터(배터리/CPU)는 `pip install '.[monitor]'`(psutil, 선택).

## 구현 현황(로드맵 `docs/ROADMAP_LAUNCH_300.md`)
- 차수 1: 프로토콜·설정·CLI ✅
- 차수 2: WS 연결·인증·재연결
- 차수 3: Ollama 호출·동시성·상태 보고
- 차수 4: 단위 테스트
- 차수 5: 중앙 서버 실연동
