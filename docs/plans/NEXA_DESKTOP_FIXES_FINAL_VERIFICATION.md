# NEXA 데스크톱 4문제 수정 — 최종 검증 체크리스트

작성일: 2026-06-06 · 브랜치: `feat/nexa-desktop-runtime-health`

이 문서는 NEXA 데스크톱 런타임 health / 재오픈 / 모델 UX / 이미지 제공 **4개 문제 수정**을
**한방에 `main` 머지**하기 직전과 직후에 무엇을 확인해야 하는지 정리한 체크리스트다.
(계획·원인규명은 `NEXA_DESKTOP_RUNTIME_HEALTH_AND_REOPEN_PLAN.md` 참조.)

> 핵심 주의: `main` 은 자주 머지하는 곳이 아니다. 머지 한 번이 **agent 자동릴리스 + central-server
> 배포**를 동시에 트리거하므로, 아래 머지 전 게이트가 **전부 그린**일 때만 머지한다.

---

## 0. 수정 요약 (무엇을 고쳤나)

| 문제 | 커밋 | 핵심 | 로컬 검증 | device 재검증 필요 |
|---|---|---|---|---|
| **P4** 이미지 토글 ON인데 `/imagine` "provider 없음" | `cbdae44d` | `applyToBackground`→`service.kickstart`로 백그라운드 서비스 재시작(새 `enable_image` 라이브 반영) + `status.sdInstalled` 가시화 | ✅ 단위+라이브 | ⚠️ 전체 Discord 왕복 |
| **P1** health 미구분(미설치 vs daemon-down) | `45cabc54`,`7513815a` | `_detect_ollama`(installed/ready/models) + `/api/models`에 `ollamaInstalled`·`ollamaReady` + GUI 메시지·칩 | ✅ 단위+실Ollama 라이브 | — (완전 검증됨) |
| **P2** macOS 재오픈 실패 | `23491106` | `.app`에 콘솔 helper(`Contents/MacOS/nexa-service`) 번들, LaunchAgent가 GUI 바이너리 대신 helper 실행(번들이 GUI앱 등록 안 됨) + 폴백 | ✅ 단위+spec파싱 | ⚠️ `.app` 빌드 후 더블클릭 |
| **P3** 모델 카탈로그/임의설치/상태 UX | (구현 예정) | 추천 catalog SSOT + `/api/ollama/catalog`·`model-install`·`models/select` + 프런트 3상태 배지 | (구현 후) | ⚠️ 설치/제공 흐름 |

`622a2bb1`(exaone 기본 모델 SSOT·브랜딩 상수)·`d436448b`(앱 아이콘)·`aacb32a7`(계획문서)는 동반 WIP.

---

## 1. 머지 전 게이트 (PRE-MERGE — 전부 그린이어야 머지)

### 1-1. provider-agent (Python) — ✅ 현재 그린
```bash
VENV=/Users/osuma/coding_stuffs/discord-assitant/.venv   # 또는 provider-agent 전용 venv
cd provider-agent
$VENV/bin/ruff check src tests                                   # ✅ All checks passed
PYTHONPATH=src $VENV/bin/mypy src/provider_agent                 # ✅ no issues (26 files)
PYTHONPATH=src $VENV/bin/python -m pytest -q --cov=provider_agent --cov-fail-under=70  # ✅ 238 passed, 73.86%
```
> 주의: CI 는 Python **3.12**(`provider-agent-ci.yml`). 로컬 검증은 3.14 venv 라 일부 asyncio
> DeprecationWarning 이 뜨지만 실패는 아니다. CI(3.12)에서 최종 확인된다.

### 1-2. 저장소 SSOT 드리프트 — ✅ 현재 그린
```bash
python3 scripts/check_packaging.py        # ✅ 18개 소비처 일치
python3 scripts/gen_i18n.py --check        # ✅ ko/en/ja 완전, 3모듈
```

### 1-3. central-server (Kotlin) — ⚠️ **머지 전 반드시 실행** (WIP `622a2bb1`이 InstallGuide.kt 변경)
```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-21.jdk/Contents/Home
central-server/gradlew -p central-server build   # ktlint + Kover(≥68%) + ArchUnit + InstallPageSsotTest
```
- 변경 파일: `InstallGuide.kt`, `InstallPageSsotTest.kt` — **InstallPageSsotTest 가 InstallGuide SSOT 런타임 주입을 검증**하므로 통과해야 한다.
- 이 빌드가 그린이 아니면 머지 후 `central-server-deploy` 가 깨진다.

### 1-4. 에이전트 빌드 스펙 사전 점검(로컬 빌드 가능 시 권장)
P2 가 `agent.spec` 에 헤드리스 helper(`nexa-service`) EXE 를 **새로 추가**했다. 로컬 PyInstaller 빌드가
가능하면 머지 전에 한 번 빌드해 helper 가 `.app` 안에 들어가는지 확인하면 좋다(불가하면 CI 에서 확인):
```bash
cd provider-agent && pip install .[gui] && pyinstaller packaging/agent.spec
ls dist/NEXA.app/Contents/MacOS/        # NEXA  +  nexa-service  둘 다 있어야 함
codesign -dv --verbose=4 dist/NEXA.app/Contents/MacOS/nexa-service 2>&1 | head   # (서명 후)
```

---

## 2. 머지가 트리거하는 것 (MERGE CONSEQUENCES)

`feat/nexa-desktop-runtime-health` → `main` 머지 시:

1. **agent 자동릴리스** — `provider-agent/**` 변경 → `agent-build.yml`
   - 멀티플랫폼 빌드(NEXA.app / NEXA.exe / CLI), `agent-vX.Y.Z` 태그 자동 증가, GitHub Release.
   - macOS: `codesign --deep`(→ 새 `nexa-service` helper 까지 서명) + notarize + staple.
2. **central-server 배포** — `central-server/**` 변경(InstallGuide.kt) → `central-server-image`(GHCR) → `central-server-deploy`(self-hosted).

> 즉 머지 = **데스크톱 앱 신버전 릴리스 + 서버 재배포**. 두 파이프라인이 모두 그린이어야 완료.

---

## 3. 머지 후 CI/릴리스 확인 (POST-MERGE)

GitHub Actions(레이트리밋 주의 — `gh run watch` 대신 ≥20s 간격/웹 확인):
- [ ] `provider-agent-ci`(3.12) 그린 — ruff/mypy/pytest+cov.
- [ ] `agent-build` 그린 — 4 플랫폼 빌드 성공. **macOS 잡 로그에서 `nexa-service` 서명/공증 통과** 확인.
  - 특히: `codesign --verify --strict "dist/NEXA.app"` 가 nested helper 포함 통과했는지.
  - notarization(`notarytool submit`)이 helper 거부 없이 통과했는지(Apple 시크릿 설정 시).
- [ ] 새 `agent-vX.Y.Z` Release 발행 + 자산(`nexa-macos.zip`/`.dmg`) 존재.
- [ ] `central-server-image` → `central-server-deploy` 그린 + 헬스체크 정상.

---

## 4. 인앱 업데이트 (실행 앱 == 수정 코드 만들기)

device 재검증 전에 **반드시** 실행 중 NEXA 를 신버전으로 올린다(현재 설치본은 구버전 0.30.x).
- GUI: 자동/수동 인앱 업데이트(프로그래스바) → 새 버전으로 교체·재실행.
- 또는 재설치: `brew upgrade --cask nexa` 혹은 새 `.dmg` 설치.
- 확인: GUI 하단/about 의 버전이 새 `agent-vX.Y.Z` 인지.

> P2 검증은 **반드시 신버전 .app**(helper 포함)에서 한다. 구버전엔 helper 가 없어 의미 없음.

---

## 5. Device 재검증 (로컬에서 불가했던 핵심 — 신버전 앱에서)

### 5-1. P2 — macOS 재오픈 (가장 중요, 실기기 H1 확정건의 수정 확인)
사전: 신버전 설치, 자동시작(`--service`) 등록, GUI 창 닫아 백그라운드 인계.
```bash
UID=$(id -u)
# (a) plist 가 이제 helper 를 가리키는지 — 수정의 핵심
grep -A1 ProgramArguments "$HOME/Library/LaunchAgents/world.yeon.nexa.plist"
#   기대: <string>.../NEXA.app/Contents/MacOS/nexa-service</string><string>--service</string>
#   (구버전은 .../MacOS/NEXA 였음)
# (b) 백그라운드 헤드리스 가동 확인
launchctl print "gui/$UID/world.yeon.nexa" | grep -E "state =|program ="
ps aux | grep -i NEXA | grep -v grep
# (c) 재오픈 테스트: 백그라운드만 떠 있는 상태에서
open /Applications/NEXA.app
sleep 3; ps aux | grep -i "MacOS/NEXA " | grep -v grep   # 새 GUI PID 가 떠야 함
```
- [ ] **기대: 더블클릭/`open` 시 설정 창이 열린다**(새 GUI PID 생성). 구버전의 `error -600`·창 안 뜸이 사라짐.
- [ ] 열린 창이 "백그라운드에서 실행 중 · 이 창은 설정 변경용" (backgroundRunning 설정모드) 로 보인다.
- [ ] `lsappinfo`에서 헤드리스 helper 가 GUI 앱(Foreground)으로 등록되지 **않는다**.

### 5-2. P4 — 이미지 토글 → 실제 `/imagine`
사전: 신버전, 토큰 연동, 로컬 SD(A1111) 설치·기동.
- [ ] GUI 에서 이미지 제공 토글 ON → `~/.config/nexa/config.json` 의 `enable_image` 가 `true` 로 바뀐다.
- [ ] 백그라운드 연결 중이면 "백그라운드에 적용(재시작)" 배너가 뜨고, 누르면 서비스가 재시작된다.
- [ ] 재시작 후 에이전트 로그(`~/Library/Logs/world.yeon.nexa.log`)에 image capability 광고/`provider_hello` 가 보인다.
- [ ] Discord 에서 `/imagine` 이 **실제 이미지 생성**으로 이어진다(아래 문구가 **안** 뜸):
  ```
  ⚠️ 🖼️ 이미지 생성 가능한 프로바이더가 없습니다. ...
  ```
- [ ] SD 미설치 상태에서 토글 ON 시 GUI 가 "이미지: SD 미설치 ⚠️" 로 안내(원인 가시화).

### 5-3. P1 — Ollama health 분리
- [ ] Ollama daemon 끄기(`pkill ollama` 또는 앱 종료) → GUI 가 "Ollama 꺼짐" + "Ollama 시작 + 기본 모델 받기" 안내(미설치 아님).
- [ ] Ollama 실행 파일까지 제거한 환경 → "Ollama 미설치" 로 표시.
- [ ] daemon 켜고 모델 있음 → 칩 "Ollama 실행 중".

### 5-4. P3 — 모델 카탈로그/설치/제공 (구현 후)
- [ ] 온보딩 이후 실사용 화면에 설치 가능 추천 모델 catalog 가 보인다(`exaone3.5:7.8b` 기본/추천).
- [ ] 모델 선택→설치→진행률→완료→"제공 대상으로 선택" 흐름 동작.
- [ ] "설치됨 / 선택됨 / Discord 에 광고 중" 3상태가 구분 표시된다.

---

## 6. 완료 기준 (계획 문서 대비)

- [ ] 창 닫아도 Discord provider 연결 유지(기존). 백그라운드 중에도 응용 프로그램에서 NEXA 재오픈 시 **GUI 설정 창이 열림(P2)**.
- [ ] Discord provider / Ollama / SD 상태가 분리 표시되고, Ollama/SD 의 미설치·설치됨꺼짐·실행중·모델없음이 구분됨(P1, P3, P4-status).
- [ ] 이미지 제공 ON + SD ready + advertised 면 `/imagine` 이 실제 생성으로 이어지고, 그 상태에서 "provider 없음" 문구가 안 뜸(P4).
- [ ] 자동 백그라운드 시작은 **설치된 런타임만** 기동(미설치를 몰래 설치 안 함) — 기존 정책 유지.
- [ ] provider-agent 게이트(ruff/mypy/pytest≥70/check_packaging) 통과(✅ 로컬), CI(3.12) 통과.

---

## 7. 롤백

- 앱: 이전 `agent-vX` Release 자산으로 재설치(인앱 다운그레이드 불가 시 수동).
- 서버: `central-server/docs/OPERATIONS.md`·`RUNBOOK.md` 의 롤백 절차(이전 GHCR 이미지로).
- 코드: `main` 에서 머지 커밋 revert 후 재배포.

---

## 부록 — 확정된 식별자(실기기 확인됨)

- LaunchAgent label: `world.yeon.nexa` · plist: `~/Library/LaunchAgents/world.yeon.nexa.plist`
- 번들 ID: `world.yeon.nexa.provider-agent` · 로그: `~/Library/Logs/world.yeon.nexa.log`
- singleton 락 포트: `48569` · config: `~/.config/nexa/config.json`
- 번들 helper(신규): `NEXA.app/Contents/MacOS/nexa-service`
