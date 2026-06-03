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
# 또는 배포물: GitHub Release 단일 실행파일(서명·체크섬·SBOM·attestation 제공)

# Discord 에서 /provider-join → 토큰 받기 → 실행(일반 사용자 권한, sudo 불필요):
discord-ai-network-bot --token ABC-DEF-GHI --relay-url ws://<서버>:8080/agent --model llama3.1:8b
# 또는 환경변수: AGENT_TOKEN, RELAY_URL, OLLAMA_BASE_URL
```
첫 실행 시 **사용량 제한·서버 주소·Ollama 주소·개인정보 안내**를 보여주고 동의를 받습니다.
서비스/스크립트에서는 `--yes` 또는 `AGENT_ACCEPT_TERMS=1` 로 사전 동의하세요.

## 한 번 설정, 자동 연결 (set-and-forget)
매번 터미널에 토큰을 다시 칠 필요 없이, **한 번만 설정**하면 로그인할 때마다 알아서 풀에 연결됩니다.

**가장 쉬운 길 — 브라우저 설정 UI(`--gui`)**: 터미널 명령을 외울 필요 없이 클릭으로 설정.
```bash
discord-ai-network-bot --gui     # 127.0.0.1 설정창이 브라우저로 열림 → 토큰 붙여넣고 저장
```
- **모델은 로컬 Ollama 에서 자동 감지**되어 체크박스로 고른다(외워서 칠 필요 없음).
  **중앙 서버 주소는 고정**(읽기 전용). 이미지 제공·자동 시작은 체크박스.
- **[시작]/[중지]** 버튼 + **라이브 상태(연결·처리 수)·로그**까지 같은 화면에서.
- 로컬 전용(127.0.0.1) + 세션 키로 보호.

**터미널로 한 번에(동일 결과):**
```bash
discord-ai-network-bot --token <1회용토큰> --relay-url wss://discord-ai.yeon.world/agent \
  --save-config --install-service
```
- 인증에 성공하면 서버가 **재사용 가능한 토큰**(durable)을 내려주고 에이전트가 저장합니다 →
  재연결·재시작·재부팅에도 `/provider-join` 을 다시 할 필요가 없습니다(만료 전까지).
- `--install-service` 는 macOS LaunchAgent / Linux `systemctl --user` / Windows 작업 스케줄러에
  **사용자 단위**로 등록(시스템 권한·서비스 등록 불필요). 로그인 시 자동 실행.
- 이미지도 제공하려면 위 명령에 `--enable-image` 를 더하면 됩니다(아래 참고). 텍스트·이미지는
  **중복 선택**(둘 다 켜기) 가능합니다.

## 일반 사용자 권한으로 동작
- **관리자/sudo 불필요.** 시스템 폴더 쓰기·서비스 등록·방화벽/레지스트리 변경을 하지 않는다.
- 설정/로그는 사용자 홈에만 저장: Windows `%APPDATA%`(또는 `XDG_CONFIG_HOME`),
  macOS/Linux `~/.config/discord-ai-network-bot/`(시크릿 파일 `0600`).
- 상시 구동은 사용자 단위 서비스(`systemctl --user`, launchd LaunchAgent)로 등록.

## 안전 기본값
- `--daily-limit` 기본 **15**(0=무제한은 `--allow-unlimited` 명시 필요), `--max-concurrency` 기본 **1**.
- Ollama 는 기본 **localhost 전용**. LAN/public/외부 주소는 차단되며 `--allow-remote-ollama` 로만 허용.
- CPU 고부하·배터리 방전 중 **자동 일시중지**(`--run-on-battery` 로 배터리 중에도 계속).
- 응답 크기 상한으로 폭주 응답을 잘라낸다.

## 이미지 프로바이더(선택) — 누구나 가능
로컬 **Stable Diffusion**(AUTOMATIC1111 등, `--api`)을 켜고 `--enable-image` 를 추가하면 `/imagine`
요청을 받는 이미지 프로바이더가 된다(텍스트와 동일한 풀, 별도 인프라 불필요).
```bash
discord-ai-network-bot --token <토큰> --relay-url wss://<서버>/agent --enable-image
# SD 주소 변경: --sd-url http://127.0.0.1:7860 (기본). 원격은 --allow-remote-sd 명시 시만.
```
- SD 도 **localhost 전용**(netguard), 옵션 화이트리스트·해상도/steps 상한. GPU 권장.
- 점검: `--self-test --enable-image`. 자세히: [`docs/IMAGE_PROVIDER.md`](../docs/IMAGE_PROVIDER.md).

## 동작
```
[내 PC] Ollama(localhost:11434) ← 에이전트 ──outbound wss──▶ [중앙 서버] ──▶ Discord
```
- inbound 포트를 열지 않는다(outbound only). Ollama 포트를 외부에 공개하지 않는다.
- 토큰은 일회용. **프롬프트 내용은 로그/파일에 남기지 않는다.**
- 서버가 지시할 수 있는 명령은 **infer/cancel/ping** 뿐. 그 외/알 수 없는 프레임은 거부·로그.

## 배포물 검증
GitHub Release 에서 받고 무결성을 검증하세요(상세: 루트 [README "안전한 설치 기준"](../README.md#안전한-설치-기준)).
```bash
sha256sum -c SHA256SUMS.txt --ignore-missing        # Linux (macOS: shasum -a 256 -c)
gh attestation verify discord-ai-network-bot-linux --repo Hyeonjun0527/discord-ai-network-bot
```

## 경량 의존성
`aiohttp` 만 필수(봇 의존성 없음). 자원 모니터(배터리/CPU)는 `pip install '.[monitor]'`(psutil, 선택).

## 구현 현황(로드맵 `docs/ROADMAP_LAUNCH_300.md`)
- 차수 1: 프로토콜·설정·CLI ✅
- 차수 2: WS 연결·인증·재연결
- 차수 3: Ollama 호출·동시성·상태 보고
- 차수 4: 단위 테스트
- 차수 5: 중앙 서버 실연동
