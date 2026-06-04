# 서명/공증 세팅 런북 — 직접 다운로드 무경고 설치

목표: 사용자가 사이트에서 받은 설치 파일을 **경고 없이** 설치. macOS=Apple Developer ID 서명+공증, Windows=**Azure Trusted Signing**. CI 파이프라인(`.github/workflows/agent-build.yml`)은 이미 있고, **인증서/시크릿만** 채우면 켜진다.

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

## B. Windows — Azure Trusted Signing

> EV 인증서(고가·HW 토큰) 대신 Microsoft의 Trusted Signing 사용. 요금 저렴(요금은 포털에서 확인, 대략 월 $단위). 단 **신원 검증** 필요(⏳). 우리 CI는 PFX/signtool → **Trusted Signing 액션으로 교체 예정**(개발자=Claude 가 코드 처리).

### B1. ☁️ Azure 구독
- https://portal.azure.com 가입(구독 없으면 생성).

### B2. ☁️ Trusted Signing 계정 생성
- 포털 상단 검색 **"Trusted Signing"** → **Create** → 리소스 그룹/리전/계정 이름 입력.

### B3. ⏳ Identity validation (신원 검증)
- Trusted Signing 계정 → **Identity validations → New** → 개인(Individual) 또는 조직(Organization) 검증 진행. 서류/확인에 시간 소요(수일 가능). 
- ※ 자격 요건(국가·개인/조직 조건)은 포털 안내를 따른다(요건이 안 맞으면 조직 검증 필요).

### B4. ☁️ Certificate Profile 생성
- 계정 → **Certificate profiles → Create** → 종류 **Public Trust** → 검증된 Identity 연결. 프로필 이름 메모(=`profile name`).

### B5. ☁️ 서명용 서비스 주체(앱 등록) + 권한
- Microsoft Entra ID → App registrations → New registration → 클라이언트 시크릿 생성. → `AZURE_TENANT_ID` · `AZURE_CLIENT_ID` · `AZURE_CLIENT_SECRET`.
- Trusted Signing 계정 IAM(Access control) → 이 앱에 **"Trusted Signing Certificate Profile Signer"** 역할 부여.
- 메모: **endpoint**(예: `https://eus.codesigning.azure.net/`, 리전별 상이) · **account name** · **profile name**.

### B6. ☁️ GitHub 시크릿 등록
`AZURE_TENANT_ID` · `AZURE_CLIENT_ID` · `AZURE_CLIENT_SECRET` · `TRUSTED_SIGNING_ENDPOINT` · `TRUSTED_SIGNING_ACCOUNT` · `TRUSTED_SIGNING_PROFILE`.

### B7. 💻(Claude) CI 교체
- `agent-build.yml` Windows 서명 단계를 `azure/trusted-signing-action` 으로 교체(현재 signtool+PFX 대신). → 별도 작업으로 진행.

---

## C. 활성화 + 검증

1. ☁️ 저장소 Settings → Secrets and variables → Actions → **Variables** 에 `REQUIRE_SIGNED_RELEASE = true` 추가(미서명 릴리스 차단).
2. 새 릴리스 태그(`agent-v*`) push → CI 가 서명/공증/스테이플 + Windows 서명 수행.
3. 검증: macOS `spctl -a -vvv 냥시스턴트.app`(accepted), `xcrun stapler validate`; Windows `signtool verify /pa`. 실제 다운로드해서 경고 없는지 확인.

## 비용 요약
- Apple Developer Program **$99/년** (필수, 공증의 전제).
- Azure Trusted Signing: 월 구독(포털 확인) + 신원 검증.
- 둘 다 **외부 계정/결제** — 코드로 대체 불가.
