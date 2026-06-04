# 로컬 개발 편의 (LAUNCH 차수 17). `make help` 로 목록 확인.
JAVA_HOME ?= /Library/Java/JavaVirtualMachines/amazon-corretto-21.jdk/Contents/Home
PY := .venv/bin
CENTRAL := central-server/gradlew -p central-server

.PHONY: help central-build central-test agent-test agent-lint e2e compose-up compose-down contract wire-gen wire-check

help:  ## 사용 가능한 타깃
	@grep -E '^[a-zA-Z-]+:.*##' Makefile | sed 's/:.*## /\t/'

central-build:  ## central-server 빌드+테스트(JDK 21)
	JAVA_HOME=$(JAVA_HOME) $(CENTRAL) build --no-daemon --console=plain

central-jar:  ## central-server bootJar(app.jar)
	JAVA_HOME=$(JAVA_HOME) $(CENTRAL) bootJar --no-daemon --console=plain

agent-test:  ## provider-agent 테스트
	cd provider-agent && PYTHONPATH=src ../$(PY)/python -m pytest tests/ -q

agent-lint:  ## provider-agent ruff+mypy
	cd provider-agent && ../$(PY)/ruff check src tests && ../$(PY)/mypy src/provider_agent

wire-gen:  ## 와이어 계약 SSOT(protocol/wire-contract.json)에서 Kotlin/Python 상수 재생성
	$(PY)/python scripts/gen_wire_contract.py

wire-check:  ## 와이어 생성물이 SSOT 와 동기인지 검증(드리프트 시 실패)
	$(PY)/python scripts/gen_wire_contract.py --check

contract: wire-check  ## 크로스언어 컨트랙트 테스트(양측) + 와이어 생성물 드리프트 검증
	cd provider-agent && PYTHONPATH=src ../$(PY)/python -m pytest tests/test_contract.py -q
	JAVA_HOME=$(JAVA_HOME) $(CENTRAL) test --no-daemon --tests '*WireContractTest*'

packaging-check:  ## 패키지 자산명 SSOT(packaging/assets.json) 드리프트 검사
	python3 scripts/check_packaging.py

i18n-gen:  ## 문구 SSOT(i18n/messages.json)에서 모듈별 생성본(봇/웹/앱) 재생성
	python3 scripts/gen_i18n.py

i18n-check:  ## 문구 생성본이 SSOT 와 동기 + ko/en/ja 완전한지 검증(드리프트 시 실패)
	python3 scripts/gen_i18n.py --check

e2e:  ## 로컬 E2E 실연동(mock Ollama+서버+에이전트)
	$(PY)/python scripts/e2e_local.py

compose-up:  ## Docker compose 기동(Postgres+central)
	cd central-server && docker compose up -d --build

compose-down:  ## Docker compose 정리
	cd central-server && docker compose down -v
