# provider-agent/AGENTS.md — Provider Agent 변경 경계

이 파일은 `provider-agent/` 하위 전체에 적용된다. 루트 `../AGENTS.md` 를 먼저 따르고,
여기서는 Provider Agent 전용 변경 제한만 추가한다.

## 역할

`provider-agent` 는 사용자의 로컬 PC에서 실행되는 경량 Python 에이전트다. 중앙 서버와 WebSocket
계약으로 연결되고, 로컬 Ollama/이미지 백엔드/데스크톱 WebUI/패키징을 소유한다.

NEXA 중앙 서버 기능을 구현할 때 이 디렉터리는 기본적으로 **변경 금지 경계**다. central-server
문제를 빠르게 해결하려고 agent 추론 경로를 직접 수정하면 로컬 배포판, 프로토콜, 데스크톱 앱 계약이
동시에 깨질 수 있다.

## 변경 허용 조건

다음 중 하나가 명확할 때만 `provider-agent/` 를 수정한다.

- 현재 작업의 권장 경로나 수용 조건이 `provider-agent/` 변경을 직접 요구한다.
- `protocol/wire-contract.json` 이 바뀌어 `make wire-gen` 으로
  `src/provider_agent/_wire_contract_generated.py` 를 재생성해야 한다.
- 데스크톱 앱 계약이 바뀌어 루트 규칙의 `prototypes/desktop` → `make sync-desktop` 흐름과
  webui 라우트/shape 검증을 함께 수행해야 한다.
- `i18n/messages.json` 또는 `packaging/assets.json` 같은 루트 SSOT 변경으로 생성본/패키징 산출물
  동기화가 필요하다.
- provider-agent 자체 결함을 재현했고, agent 테스트/로그/실행 증거로 수정 범위가 확인됐다.

위 조건이 없으면 central-server 작업 중 `agent.py`, `protocol.py`, `webui.py`, 로컬 모델 호출 코드,
패키징 파일을 편의상 수정하지 않는다.

## 금지사항

- central-server 라우팅/정책 버그를 숨기기 위해 agent 추론 결과, 옵션, timeout, retry 동작을 바꾸지 않는다.
- `src/provider_agent/_wire_contract_generated.py` 는 직접 편집하지 않는다. SSOT 는
  `../protocol/wire-contract.json` 이고 생성 명령은 루트의 `make wire-gen` 이다.
- `src/provider_agent/webui_assets/` 는 생성물이다. 직접 고치지 말고 `../prototypes/desktop` 을 수정한 뒤
  루트의 `make sync-desktop` 을 실행한다.
- 사용자의 로컬 토큰, 설정 파일, 로그에 포함된 비밀값을 출력하거나 커밋하지 않는다.
- mock/데모 동작을 실 앱 경로에 넣지 않는다. 프로토타입 전용 표현은 루트 규칙의 `@proto-only` 구간으로
  격리한다.

## 수정 시 동기화 체크

- Wire 프로토콜: `make wire-gen` 후 `make contract`.
- 데스크톱/WebUI: `make sync-desktop`, `make desktop-check`, 필요 시 `cd ../prototypes/desktop && npx playwright test`.
- i18n: 루트 `i18n/messages.json` 수정 후 `make i18n-gen`, `make i18n-check`.
- 패키징 자산명: 루트 `packaging/assets.json` 수정 후 `make packaging-check`.

## provider-agent 검증

코드나 생성본을 건드렸다면 최소한 아래를 통과시킨다.

```bash
cd provider-agent && ../.venv/bin/python -m pytest -q --cov=provider_agent --cov-fail-under=70
../.venv/bin/ruff check src tests
../.venv/bin/mypy src
```

문서만 바꾼 경우에도 루트에서 `python3 scripts/check_links.py` 와 `git diff --check` 를 실행한다.
루트의 `./scripts/nexa-verify.sh agent` 가 생긴 뒤에는 해당 wrapper를 우선 사용한다.
