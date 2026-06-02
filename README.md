# 커뮤니티 로컬 AI Provider Pool

커뮤니티 멤버들이 **각자 PC 의 로컬 LLM(Ollama)** 을 풀에 기여하고, 디스코드에서 `/ask` 로
다른 유저가 그 LLM 들을 **공정하게 나눠 쓰는** 시스템입니다. 판매·결제가 아니라 **기여·동의·
가용성·공정성**이 핵심입니다(ADR 0003/0004).

> 기존 단일 Python 요약/Q&A 봇은 2026-05-30 제거되고 본 시스템으로 단일화되었습니다
> (이력: [`docs/BOT_MIGRATION.md`](docs/BOT_MIGRATION.md)).

## 어떻게 동작하나요?

```
유저 /ask  →  central-server(공정성 라우팅)  →  어느 프로바이더의 PC Ollama  →  응답
```

- **유저**: `/ask <질문>` 으로 풀의 누군가의 PC LLM 에게 묻습니다. `/models` `/catalog` `/help`.
- **프로바이더**: `/provider-join` → 승인 → 토큰으로 자기 PC 에서 에이전트 실행 → 풀에 기여.
  포트 개방 불필요(아웃바운드 전용). `/provider-schedule` 로 가용 시간대 설정.
- **관리자**: 승인·정책(채널/역할)·차단·공정성 리포트로 서버를 운영합니다.

## 구성 요소

| 디렉터리 | 설명 |
|---|---|
| [`central-server/`](central-server/) | Provider Pool 중앙 서버 + Discord 봇 (Kotlin/Spring Boot, JDA). 라우팅·정책·관측성·웹 대시보드 |
| [`provider-agent/`](provider-agent/) | 유저 PC용 경량 에이전트 (Python/aiohttp). 로컬 Ollama 를 풀에 연결 |
| [`specs/product-v2/`](specs/product-v2/) | Provider Pool 제품 명세(요구사항/도메인/화면/API/추적성) |
| [`docs/`](docs/) | ADR·로드맵·운영/베타 문서 |

## 빠른 시작 (로컬 E2E)

```bash
# JDK 21 필요
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
python3 -m venv .venv && .venv/bin/pip install -e provider-agent
.venv/bin/python scripts/e2e_local.py   # mock Ollama + 서버 + 에이전트 → /ask 실왕복
```

## 라이브로 띄우기 (Discord 봇)

토큰 발급 → 기동 → 프로바이더 온보딩 → 검증 절차는
[`central-server/docs/GO_LIVE.md`](central-server/docs/GO_LIVE.md) 참고.

```bash
cd central-server
DISCORD_ENABLED=true DISCORD_BOT_TOKEN=<토큰> CENTRAL_DEV_ENABLED=false \
  docker compose up -d --build
```

## 빌드 / 검증

```bash
# central-server (Kotlin)
central-server/gradlew -p central-server build      # test + ktlint + 커버리지 게이트

# provider-agent (Python)
cd provider-agent && ../.venv/bin/python -m pytest -q
../.venv/bin/ruff check src tests && ../.venv/bin/mypy src
```

규칙·배포·릴리스 등 공용 규약은 [`AGENTS.md`](AGENTS.md) 가 SSOT 입니다.

## 안전한 설치 기준

Provider Agent 는 "믿고 설치하세요"가 아니라 **사용자가 검증할 수 있고 기본값이 안전한**
프로그램을 지향합니다. 설치 전 아래를 확인하세요.

**1) 출처와 무결성을 검증한다**

- 반드시 **GitHub Release** 에서 받습니다. 설치 페이지의 명령은 항상
  `releases/latest/download/**` 만 가리킵니다.
- 함께 받은 `SHA256SUMS.txt` 로 해시를 검증합니다.
  - macOS: `shasum -a 256 -c SHA256SUMS.txt --ignore-missing`
  - Linux: `sha256sum -c SHA256SUMS.txt --ignore-missing`
  - Windows: `Get-FileHash <파일> -Algorithm SHA256` 출력과 비교
- 빌드 출처(provenance)·SBOM 을 확인할 수 있습니다:
  `gh attestation verify discord-ai-provider-agent-<os> --repo Hyeonjun0527/discord-assistant`
- 배포물은 **Windows 코드서명**, **macOS Developer ID 서명 + notarization** 이 적용됩니다.
  경고가 뜨면 **우회하지 말고** 출처/해시를 다시 확인하세요.

**2) 관리자 권한이 필요 없다**

- 에이전트는 **일반 사용자 권한**으로 동작합니다. `sudo`·관리자 PowerShell 이 필요 없습니다.
- 시스템 폴더 쓰기·Windows 서비스 등록·방화벽/레지스트리 변경을 하지 않습니다.
- 설정·로그는 사용자 홈에만 저장됩니다(Windows `%APPDATA%`, macOS
  `~/Library/Application Support`/`~/.config`, Linux `~/.config`).

**3) 기본값이 안전하다**

- 하루 처리 한도 **15건**·동시 **1건** 이 기본. 무제한은 `--allow-unlimited` 명시로만.
- Ollama 는 기본 **localhost 전용**. 원격은 `--allow-remote-ollama` 위험 확인에서만.
- CPU 고부하·배터리 방전 중 자동 일시중지. 첫 실행 시 동의 화면을 표시합니다.

**4) 개인정보에 주의한다**

- `/ask` 질문 내용은 처리하는 **다른 사용자의 PC(로컬 AI)로 전송**될 수 있습니다.
- **비밀번호·API 키·토큰·개인정보는 절대 입력하지 마세요.** 에이전트는 프롬프트 원문을
  로그/파일에 저장하지 않습니다.

보안 정책·취약점 신고는 [`SECURITY.md`](SECURITY.md) 를 참고하세요.

## 문서

- 운영: [`central-server/docs/OPERATIONS.md`](central-server/docs/OPERATIONS.md) · [`RUNBOOK.md`](central-server/docs/RUNBOOK.md) · [`GO_LIVE.md`](central-server/docs/GO_LIVE.md)
- 사용 가이드: [`docs/FAQ.md`](docs/FAQ.md) · 베타: [`docs/BETA.md`](docs/BETA.md)
- 로드맵: [`docs/ROADMAP_LAUNCH_300.md`](docs/ROADMAP_LAUNCH_300.md) · 회고: [`docs/RETROSPECTIVE.md`](docs/RETROSPECTIVE.md)
- 결정 기록: [`docs/adr/`](docs/adr/)
