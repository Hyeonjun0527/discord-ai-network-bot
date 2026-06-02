# 릴리스 신뢰 — 무료($0) 우선, 유료는 선택

**유료 인증서는 필수가 아니다.** 이 저장소는 공개 OSS 라서 $0 로도 "검증 가능하고 경고 없는"
배포가 가능하다. 유료(Apple $99/년, Windows 인증서)는 더 매끄러운 경험을 위한 **선택**일 뿐이다.

## 권장: $0 플랜 (지금 바로)

| 항목 | 방법 | 비용 |
|---|---|---|
| 무결성·출처 | SHA256SUMS + GitHub **artifact attestation**(OIDC) | $0 (이미 동작) |
| macOS 경고 제거 | **Homebrew tap** 또는 `curl` 설치 | $0 |
| Windows 서명 | **SignPath.io 무료 OSS 플랜** | $0 (공개 OSS) |
| Windows 배포 | **winget / Scoop**(해시 자동 검증) | $0 |

### macOS 가 $99 없이도 경고가 안 뜨는 이유
- Gatekeeper 의 "확인되지 않은 개발자" 차단은 **격리 속성(quarantine)** 이 붙은 파일에만 적용된다.
- `curl`·`brew` 로 받은 파일에는 격리 속성이 **붙지 않는다**(브라우저 더블클릭 다운로드만 붙음).
- arm64 실행에 필요한 최소 서명은 **PyInstaller 가 ad-hoc 으로 자동** 부여한다.
- 따라서 설치 안내를 `curl`/`brew` 로 유지하면 **Apple Developer($99) 없이도** 경고 없이 실행된다.
- 공증(notarization)은 *브라우저로 받아 더블클릭* 하는 경우에만 의미가 있다. 우리는 그 경로를 권장하지 않는다.

### Windows: SignPath.io 무료 OSS 서명
- SignPath Foundation 은 **검증된 오픈소스 프로젝트에 무료 코드서명**을 제공한다.
- 신청: https://signpath.org → 프로젝트 등록(공개 레포·빌드 워크플로 제출) → 승인 후 CI 에서
  SignPath GitHub Action 으로 서명. 실제 인증서 서명이라 SmartScreen 평판이 쌓인다.
- 승인 전까지는 **winget/Scoop + 체크섬 + attestation** 으로 충분(아래 참조). 경고가 보이면
  우회를 안내하지 말고 체크섬/attestation 으로 검증하게 한다.

### 검증(시크릿 없이도 오늘부터 동작)
```bash
sha256sum -c SHA256SUMS.txt --ignore-missing      # macOS: shasum -a 256 -c
gh attestation verify discord-ai-network-bot-<os> --repo Hyeonjun0527/discord-assistant
```
패키지 매니저로 설치하면 위 검증을 매니저가 자동으로 한다 → [PACKAGE_MANAGERS.md](PACKAGE_MANAGERS.md).

---

## 선택: 유료 서명 (더 매끄러운 경험)

> 시크릿이 설정되면 `agent-build.yml` 이 자동 서명/공증한다. 릴리스 태그(`agent-v*`) 빌드는
> 서명 시크릿이 없으면 워크플로가 **실패**하여 미서명 릴리스 발행을 막는다(게이트). 따라서
> 유료 경로를 쓸 거면 시크릿을 등록하고, 안 쓸 거면 게이트 조건을 손대지 말고 무료 경로로 배포한다.

### macOS (Apple Developer ID + notarization) — 선택, $99/년
브라우저 더블클릭 경로까지 깔끔하게 하려면:
1. Apple Developer → "Developer ID Application" 인증서 → Keychain 에서 `.p12` export.
2. appleid.apple.com → 앱 암호(notarytool 용) 발급.
3. GitHub 시크릿: `MACOS_CERT_P12`(base64), `MACOS_CERT_PASSWORD`, `MACOS_SIGN_IDENTITY`,
   `APPLE_ID`, `APPLE_TEAM_ID`, `APPLE_APP_PASSWORD`.

### Windows (유료 인증서) — 선택
- SignPath 무료 OSS 가 막히는 경우의 대안. OV/EV 인증서(연 $200~700) 또는 Azure Trusted
  Signing(월 ~$10). `.pfx` → 시크릿 `WINDOWS_CERT_PFX`(base64), `WINDOWS_CERT_PASSWORD`.
- **비용이 부담되면 쓰지 말 것.** 무료 SignPath/Scoop/winget + attestation 으로 충분하다.

관련: [PACKAGE_MANAGERS.md](PACKAGE_MANAGERS.md), [../SECURITY.md](../SECURITY.md).
