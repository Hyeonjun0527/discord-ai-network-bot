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

## 문서

- 운영: [`central-server/docs/OPERATIONS.md`](central-server/docs/OPERATIONS.md) · [`RUNBOOK.md`](central-server/docs/RUNBOOK.md) · [`GO_LIVE.md`](central-server/docs/GO_LIVE.md)
- 사용 가이드: [`docs/FAQ.md`](docs/FAQ.md) · 베타: [`docs/BETA.md`](docs/BETA.md)
- 로드맵: [`docs/ROADMAP_LAUNCH_300.md`](docs/ROADMAP_LAUNCH_300.md) · 회고: [`docs/RETROSPECTIVE.md`](docs/RETROSPECTIVE.md)
- 결정 기록: [`docs/adr/`](docs/adr/)
