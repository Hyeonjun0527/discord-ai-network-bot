# 서명/공증 세팅 런북 — 직접 다운로드 무경고 설치

목표: 사용자가 사이트에서 받은 설치 파일을 설치할 때 OS 경고 최소화. macOS=Apple Developer ID 서명+공증(즉시 무경고), Windows=**SSL.com IV 코드서명 + eSigner**(평판형, 즉시 무경고는 사업자+EV 필요). CI 파이프라인(`.github/workflows/agent-build.yml`)은 준비돼 있고, **인증서/시크릿 + 활성화 변수**만 채우면 켜진다. (※ Azure Trusted Signing 은 한국 미지원이라 제외.)

> 표기: ☁️=웹 클릭, 💻=Mac 로컬 작업(클릭만으로 불가), ⏳=검증 대기.

---

## A. macOS — Apple Developer ID + 공증(notarization)

### A1. ☁️ Apple Developer Program 가입 ($99/년)
- https://developer.apple.com/programs/ → **Enroll** → Apple ID 로그인 → 개인(Individual) 선택이 가장 빠름(조직은 D-U-N-S 번호 필요). 결제 $99.

### A2. 💻 Mac 키체인에서 CSR 생성
- `Keychain Access`(키체인 접근) 앱 → 메뉴 **Certificate Assistant → Request a Certificate From a Certificate Authority**
- 이메일 입력, **"Saved to disk"** 선택 → `CertificateSigningRequest.certSigningRequest` 저장.

### A3. ☁️ Developer ID Application 인증서 발급
- https://developer.apple.com/account → **Certificates, IDs & Profiles → Certificates → ＋**
- 종류: **Developer ID Application** 선택 → A2의 CSR 업로드 → 생성된 `.cer` **Download**.
- `.cer` 더블클릭 → 키체인에 설치됨.

### A4. 💻 .p12 내보내기
- `Keychain Access` → My Certificates 에서 방금 인증서(+개인키) 선택 → 우클릭 **Export** → `.p12` 저장, **비밀번호 설정**.
- base64 변환: `base64 -i cert.p12 | pbcopy` (이 값이 시크릿 `MACOS_CERT_P12`).
- 인증서 이름 확인: `security find-identity -v -p codesigning` → `"Developer ID Application: 이름 (TEAMID)"` 전체 문자열 = `MACOS_SIGN_IDENTITY`.

### A5. ☁️ Team ID + 앱 전용 비밀번호
- **Team ID**: https://developer.apple.com/account → Membership → 10자리 Team ID = `APPLE_TEAM_ID`.
- **앱 전용 비밀번호**: https://account.apple.com → **Sign-In and Security → App-Specific Passwords → ＋** → 생성한 값 = `APPLE_APP_PASSWORD`. (`APPLE_ID` = Apple ID 이메일.)

### A6. ☁️ GitHub 시크릿 등록
저장소 → Settings → Secrets and variables → Actions → New repository secret:
`MACOS_CERT_P12`(base64) · `MACOS_CERT_PASSWORD` · `MACOS_SIGN_IDENTITY` · `APPLE_ID` · `APPLE_TEAM_ID` · `APPLE_APP_PASSWORD`.

---

## B. Windows — SSL.com IV Code Signing + eSigner (클라우드)

> ⚠️ **Azure Trusted Signing 은 한국에서 불가**(개인=US/CA만, 조직=US/CA/EU/UK만 — 한국 제외). 그래서
> 한국 개인 개발자는 **SSL.com IV(Personal ID) 코드서명 + eSigner 클라우드 서명**으로 간다.
> - **IV** = 개인 명의(사업자 불필요). 단 **즉시 무경고는 불가** → SmartScreen 은 다운로드 평판이 쌓이며 점차 사라짐.
> - 즉시 무경고(EV급)는 사업자(조직)만 → 향후 사업자 등록 시 EV/Sole-Proprietor EV 로 업그레이드 고려.

### B1. ☁️ 구매
- https://www.ssl.com → Code Signing → **IV Code Signing** ($129/년~). **Key Storage = eSigner Cloud Signing** 선택(토큰 X, CI 필수). Validation = Standard.

### B2. ⏳ 신원 검증 (Individual)
- 주문 후 Validations → 개인 신원확인: **정부 신분증 앞/뒤 + 신분증 든 셀피**. (여권 권장 — 주민번호 노출 0. 주민등록증이면 주민번호 뒷자리 마스킹.)
- 검증 승인까지 수일.

### B3. ☁️ eSigner 등록 (발급 후)
- 인증서 발급되면 **eSigner 활성화** + **2FA(TOTP) 등록** → **Credential ID** 확인.

### B4. ☁️ GitHub 시크릿 등록
`ES_USERNAME`(SSL.com 로그인) · `ES_PASSWORD` · `ES_CREDENTIAL_ID` · `ES_TOTP_SECRET`(2FA seed).

### B5. ☁️ 활성화 스위치
- 저장소 Settings → Secrets and variables → Actions → **Variables** 에 `WINDOWS_SIGN_PROVIDER = esigner` 추가.

### B6. 💻(Claude) CI
- `agent-build.yml` 에 **`sslcom/esigner-codesign`** 액션으로 Windows exe 서명 단계 추가(위 변수/시크릿 게이트, 없으면 비활성). ← **선반영 완료**.

---

## C. 활성화 + 검증

1. ☁️ macOS: A 단계 시크릿 채우기 → (선택) Variables `REQUIRE_SIGNED_RELEASE = true` 로 미서명 릴리스 차단.
2. ☁️ Windows: B4 시크릿 + `WINDOWS_SIGN_PROVIDER=esigner` 설정.
3. 새 릴리스 태그(`agent-v*`) push → CI 가 macOS 공증/staple(.app **및 배포 dmg**) + Windows eSigner 서명 수행.
   - `.app` 서명+공증+staple → 그 `.app` 으로 만든 **dmg 컨테이너 자체도 notarize + staple**(Apple 시크릿이 모두 있을 때만; 드래그 설치 시 완전 무경고). 시크릿이 없으면 dmg 는 미공증으로 생성되고 체크섬/attestation 으로 검증한다.
4. 검증: macOS `spctl -a -vvv 냥시스턴트.app`(accepted)·`xcrun stapler validate 냥시스턴트.app`·`xcrun stapler validate nyassistant-macos.dmg`; Windows `signtool verify /pa nyassistant-windows.exe`. 실제 브라우저로 받아 경고 확인(mac=무경고, win=평판 쌓이는 중이면 초기 경고 가능).

## 비용 요약
- **macOS**: Apple Developer Program **$99/년** → 공증으로 **즉시 무경고**.
- **Windows**: SSL.com **IV ~$129/년 + eSigner 구독** → 서명은 되나 **즉시 무경고 아님**(평판형). 즉시 무경고는 사업자+EV 필요.
- 모두 **외부 계정/결제** — 코드로 대체 불가.
