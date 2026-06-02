# 보안 정책 (Security Policy)

커뮤니티 로컬 AI Provider Pool(중앙 서버 + Provider Agent)의 보안 정책입니다.
이 문서는 **취약점 신고 방법·지원 버전·대응 절차·릴리스 폐기 절차**를 정의합니다.

> 컴포넌트별 상세 보안 설계는 [`central-server/SECURITY.md`](central-server/SECURITY.md)
> (중앙 서버), Provider Agent 의 안전 기본값은 [`provider-agent/README.md`](provider-agent/README.md)
> 를 참고하세요.

## 취약점 신고 (Reporting a Vulnerability)

보안 취약점은 **공개 이슈로 올리지 마세요.** 비공개로 알려주세요.

- 우선: GitHub **Security Advisory**(레포 → Security → *Report a vulnerability*)를 통한
  비공개 제보(Private Vulnerability Reporting).
- 또는 메인테이너에게 직접 연락: `aiyessorae@gmail.com`.

제보에 포함해 주시면 좋은 것:

- 영향 받는 컴포넌트(central-server / provider-agent / 설치 페이지)와 버전/커밋.
- 재현 절차, 영향(권한 상승·정보 노출·DoS 등), 가능하면 PoC.
- 제안하는 완화책(있다면).

### 응답 목표(SLA, best-effort)

| 단계 | 목표 시간 |
|---|---|
| 접수 확인(ack) | 영업일 기준 72시간 이내 |
| 1차 분류(심각도 산정) | 7일 이내 |
| 수정 또는 완화 계획 공유 | 심각도에 따라 14~30일 |
| 공개(Advisory 발행) | 수정 배포 후 조율된 공개 |

개인 졸업 프로젝트로 운영되므로 상용 제품 수준 SLA 는 아닙니다. 그래도
조율된 공개(coordinated disclosure)를 원칙으로 합니다.

## 지원 버전 (Supported Versions)

보안 수정은 **최신 릴리스 라인**에만 제공합니다.

| 컴포넌트 | 지원 대상 | 비고 |
|---|---|---|
| central-server | 최신 `central-v*` 릴리스 | 이전 마이너는 best-effort |
| provider-agent | 최신 `agent-v*` 릴리스 | 이전 버전 사용자는 업그레이드 권장 |

`agent-v*` / `central-v*` 태그 기준으로 가장 최근 버전만 보안 패치를 보장합니다.
구버전 사용자는 최신으로 업그레이드하세요(에이전트는 단일 실행파일 교체로 끝).

## 대응 절차 (Response Process)

1. **접수·확인** — 제보를 비공개로 접수하고 ack 회신.
2. **분류** — 재현·영향 평가·심각도(Critical/High/Medium/Low) 산정.
3. **수정** — 비공개 브랜치에서 패치, 회귀 테스트 추가.
4. **배포** — 새 릴리스 태그 발행(서명·체크섬·SBOM·attestation 포함).
5. **공개** — GitHub Security Advisory + CHANGELOG 에 수정 사실 기록(제보자 크레딧, 동의 시).
6. **폐기 안내** — 영향 받은 구버전을 아래 절차로 폐기.

## 릴리스 폐기 절차 (Release Revocation)

배포된 릴리스에 보안 문제가 확인되면 다음을 수행합니다.

1. **마킹** — 해당 GitHub Release 노트 상단에 `⚠️ 보안 폐기(REVOKED): <사유>, <대체 버전>` 표기.
2. **권고** — Advisory 와 README/설치 페이지에 "이 버전을 쓰지 말고 최신으로 교체" 공지.
3. **배포 경로 차단** — 중앙 서버 `/download/**` 가 서빙하는 바이너리를 즉시 최신 버전으로
   교체하여, 폐기된 바이너리가 더는 배포되지 않게 한다(install 페이지는 항상
   `releases/latest/download/**` 만 가리킴).
4. **무결성 갱신** — 새 `SHA256SUMS.txt`·SBOM·attestation 으로 교체.
5. **강제 업그레이드(선택)** — 필요 시 에이전트 최소 버전 게이트로 구버전 접속을 거부.

## 설치물 무결성 검증 (사용자용)

릴리스는 다음으로 검증할 수 있습니다.

- **SHA256 체크섬**: `SHA256SUMS.txt` 와 대조.
- **코드 서명**: Windows(코드서명), macOS(Developer ID 서명 + notarization).
- **빌드 출처**: `gh attestation verify <파일> --repo Hyeonjun0527/discord-assistant`.
- **SBOM**: 릴리스의 `provider-agent-sbom.spdx.json`.
- **수동 검증 없이**: 패키지 매니저(`brew`/`winget`)는 해시를 자동 검증한다
  ([docs/PACKAGE_MANAGERS.md](docs/PACKAGE_MANAGERS.md)). 코드서명/공증 설정은
  [docs/RELEASE_SIGNING.md](docs/RELEASE_SIGNING.md).

자세한 안전 설치 기준은 [README 의 "안전한 설치 기준"](README.md#안전한-설치-기준)을 참고하세요.

## 운영 보안 원칙(요약)

- Provider Agent 는 **일반 사용자 권한**으로 동작한다(관리자/sudo·시스템 폴더·방화벽·
  레지스트리 변경 없음). 설정/로그는 사용자 홈 디렉터리에만 저장.
- Ollama 는 기본 **localhost 전용**. 원격은 명시적 위험 확인(`--allow-remote-ollama`)에서만.
- 중앙 서버가 에이전트에 지시할 수 있는 명령은 **infer/cancel/ping** 뿐이다. shell 실행·
  파일 읽기·임의 URL 호출·모델 다운로드를 지시하는 프레임은 프로토콜에 존재하지 않으며,
  알 수 없는/허용되지 않은 프레임은 거부·로그된다.
- **프롬프트 원문은 로그/DB 에 저장하지 않는다.** 토큰은 일회용·해시 저장.
