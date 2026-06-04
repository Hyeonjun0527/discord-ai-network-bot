# 계획 — 원클릭 직접 설치 + 앱 내 Ollama 셋업 (온보딩 UX 개편)

목표: 사용자가 **랜딩의 다운로드 버튼 한 번 → 경고 없이 앱 설치 → 앱이 Ollama까지 설치/모델 다운로드**. 터미널 단계 제거(고급 옵션으로만 유지).

현재 흐름(문제): `/install` 가 **터미널에서 Ollama 먼저(brew/winget) → 그다음 앱** 순서. 대부분 사용자는 Ollama가 없고 터미널이 장벽. (SSOT: `central-server/.../domain/InstallGuide.kt`)

원하는 흐름: **앱 먼저(원클릭 다운로드) → 앱 내에서 Ollama 설치/모델**.

## 사전 조건 — OS 신뢰 서명 (★ 블로커, 개발자 계정/비용 필요)

직접 다운로드로 **경고 없이** 깔리려면 OS 신뢰 서명이 필수. 파이프라인은 `agent-build.yml` 에 **이미 구현**(시크릿 없으면 skip). 활성화 = 인증서 + GitHub 시크릿 + `REQUIRE_SIGNED_RELEASE=true`.

- **macOS**: Apple Developer ID 서명 + notarization + staple. 비용 Apple Developer Program **$99/년**. 시크릿: `MACOS_CERT_P12/PASSWORD/SIGN_IDENTITY`, `APPLE_ID/TEAM_ID/APP_PASSWORD`.
- **Windows**: Authenticode 서명. **즉시 무경고**는 EV 인증서(고가·HW 토큰) **또는 Azure Trusted Signing(권장: 저렴, 조직 인증)**. 시크릿: `WINDOWS_CERT_PFX/PASSWORD`(또는 Trusted Signing 연동).
- 공급망 검증(SHA256SUMS·SBOM·attestation)은 이미 됨 — 이건 **변조 방지**지 **OS 신뢰 경고**와는 별개.

> 결론: 서명 없이는 직접 다운로드 = 경고. 서명 켜지기 전까지는 brew/winget(무경고·무료)이 안전 경로. **Phase 1·3 의 '무경고 원클릭'은 이 서명 활성화에 의존.**

## Phase 1 — 랜딩 원클릭 다운로드 (웹)

- 3D 패널의 **다운로드 버튼을 실제 클릭 가능**하게(레이캐스트 click → OS 감지 → 설치 파일 다운로드). 버튼은 이미 mac/win 2개.
- 다운로드 타깃: GitHub `releases/latest/download/<asset>` (`nyassistant-macos.zip` / `nyassistant-windows.exe`, SSOT `packaging/assets.json`). 서버 변경 없이 가능.
- brew/winget 은 "검증 설치(고급)" 로 접어 유지.
- ⚠️ 서명 활성화 전에는 경고가 뜨므로, **서명 ON 이후 기본 노출**(그 전엔 고급 옵션 뒤 또는 '우클릭 열기' 안내).

## Phase 2 — 앱 내 Ollama 자동 셋업 (provider-agent) ★핵심, 서명과 독립

현재 앱은 Ollama **감지/연결만** 하고 설치는 안 함(`webui.py` 는 "터미널에서 `ollama pull` 하세요" 안내만). 추가할 것:

1. **감지**: `/api/tags` 로 Ollama 유무 + 모델 목록(이미 `_detect_models`).
2. **설치**: 없으면 GUI에서 "Ollama 설치" → macOS `brew install ollama`(또는 공식 설치 스크립트), Windows `winget install Ollama.Ollama`. 관리자 권한 불필요 경로 우선(안전 정책 부합). 동의/진행 표시.
3. **모델 다운로드**: `ollama pull <model>` 진행률 스트리밍(이미 `ollama.py:pull` 존재 → GUI 진행바).
4. **상태/에러 처리**: 설치 실패·네트워크·디스크 안내. 비대화형/CI 가드.
- 검증: ruff/mypy/pytest(≥70%). 문구는 i18n(agent 섹션) SSOT.

## Phase 3 — 순서 뒤집기 (SSOT)

Phase 2 완료 후 `InstallGuide.kt` 재구성: **1) 앱 다운로드 → 2) 앱 열고 디스코드 로그인 → 3) 앱이 Ollama 설치/모델**. 터미널 brew/winget 은 "고급"에만. 랜딩/슬래시 안내 문구도 동기화.

## 진행 순서(권장)

1. (개발자) **인증서 발급** 결정·진행 — Apple Dev $99 + Windows Azure Trusted Signing. ← 무경고의 전제.
2. (병행, 서명 무관) **Phase 2** 구현 — 가장 큰 UX 이득.
3. 서명 ON 확인 후 **Phase 1 + Phase 3** (원클릭 다운로드 기본화 + 순서 뒤집기).

## 미해결/리스크

- 서명 인증서는 **외부 계정/비용** — 코드로 대체 불가.
- Ollama 설치 자동화 시 권한/플랫폼 차이(특히 Windows winget 가용성, 사내망 프록시).
- 직접 다운로드는 자산 안정 URL·버전 정합 필요(latest vs 핀 버전).
