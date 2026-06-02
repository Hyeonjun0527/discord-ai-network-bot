# 릴리스 코드서명·공증 설정 가이드

배포물에 코드서명/공증을 적용하면 **OS가 게시자·위변조를 자동 검증**하므로, 사용자가 수동
해시검증을 하지 않아도 된다. 인증서 발급·계정 등록은 **메인테이너 본인**이 수행해야 한다.

> 시크릿이 설정되면 `agent-build.yml` 이 자동으로 서명/공증한다. 릴리스 태그(`agent-v*`)
> 빌드는 서명 시크릿이 없으면 **워크플로가 실패**하여 미서명 릴리스 발행을 막는다.

## macOS (Apple Developer ID + notarization)

**필요한 것**: Apple Developer Program 멤버십($99/년).

1. **Developer ID Application 인증서 발급**
   - Apple Developer → Certificates → "Developer ID Application" 생성.
   - Keychain 에서 인증서+개인키를 `.p12` 로 export(암호 설정).
2. **app-specific password 발급**
   - appleid.apple.com → 로그인 및 보안 → 앱 암호 → notarytool 용 암호 생성.
3. **Team ID 확인**: Apple Developer → Membership → Team ID.
4. **GitHub 시크릿 등록**(Settings → Secrets and variables → Actions)
   | 시크릿 | 값 |
   |---|---|
   | `MACOS_CERT_P12` | `.p12` 파일을 base64 인코딩(`base64 -i cert.p12 \| pbcopy`) |
   | `MACOS_CERT_PASSWORD` | `.p12` export 암호 |
   | `MACOS_SIGN_IDENTITY` | 예: `Developer ID Application: Your Name (TEAMID)` |
   | `APPLE_ID` | Apple 계정 이메일 |
   | `APPLE_TEAM_ID` | 10자리 Team ID |
   | `APPLE_APP_PASSWORD` | 위 앱 암호 |

> 단일 실행파일은 스테이플 불가 → Gatekeeper 가 온라인으로 공증을 확인한다(`curl` 다운로드는
> quarantine 미부여라 검사조차 생략). 오프라인 첫 실행까지 보장하려면 `.pkg`(Developer ID
> Installer 서명) 패키징 후 `xcrun stapler staple` 로 스테이플한다.

## Windows (코드서명)

**권장**: **Azure Trusted Signing**(개인도 신원검증 후 가능, ~월 $10, SmartScreen 평판 즉시).
**대안**: OV/EV 코드서명 인증서(연 $200~700, EV 는 즉시 평판).

1. 인증서/Trusted Signing 계정 발급 및 신원 검증.
2. 인증서를 `.pfx` 로 export(암호 설정).
3. **GitHub 시크릿 등록**
   | 시크릿 | 값 |
   |---|---|
   | `WINDOWS_CERT_PFX` | `.pfx` 를 base64 인코딩 |
   | `WINDOWS_CERT_PASSWORD` | `.pfx` 암호 |

> Azure Trusted Signing 을 쓰면 `signtool` 대신 Trusted Signing 액션으로 교체한다(평판이
> 빠르게 쌓여 SmartScreen 경고가 사라짐). 현재 워크플로는 `signtool /f cert.pfx` 기준.

## 검증(시크릿 없이도 오늘부터 동작)

- **빌드 출처·SBOM attestation**: GitHub OIDC 로 서명되어 **무료·시크릿 불필요**.
  ```bash
  gh attestation verify discord-ai-provider-agent-<os> --repo Hyeonjun0527/discord-assistant
  ```
- **SHA256SUMS.txt**: 모든 릴리스에 첨부.

서명/공증 시크릿을 등록하기 전에도 attestation·체크섬으로 무결성·출처는 검증 가능하다.
관련: [PACKAGE_MANAGERS.md](PACKAGE_MANAGERS.md), [../SECURITY.md](../SECURITY.md).
