# 패키지 매니저 셋업 가이드 (Homebrew tap · Scoop bucket) — $0

> ✅ **이미 구성됨**: tap/bucket 레포는 org `yeon-intergation-platform` 아래에 있고
> (`yeon-intergation-platform/homebrew-tap`, `.../scoop-bucket`), `agent-v0.1.2` 로 채워졌다.
> 또한 **버전은 자동 증가**한다 — `agent-autorelease.yml` 이 main 의 provider-agent 코드 변경에서
> 다음 SemVer 를 계산해 태그를 끊고, `agent-build.yml` 이 릴리스+패키지매니저 갱신을 잇는다.
> 남은 수동 단계는 **PAT 등록(3단계)** 하나뿐. 아래는 처음부터 다시 구성할 때의 참고 절차다.

이 문서는 **메인테이너가 한 번만** 수행하는 셋업이다. 끝나면 사용자는 `brew install` /
`scoop install` 한 줄로 설치하고, 무결성(sha256)은 매니저가 자동 검증한다(수동 해시검증 불필요).

전제: 저장소가 **PUBLIC**(OSS)여야 한다(릴리스 자산 URL 공개 접근). 모든 명령은 `gh`(로그인 됨)와
`git` 로 동작한다.

---

## 0단계: 첫 에이전트 릴리스 만들기 (매니페스트가 여기서 생성됨)

패키지 매니저 매니페스트는 릴리스 자산에 **실제 version·sha256 으로 렌더되어** 첨부된다.
그러니 먼저 릴리스를 한 번 끊는다.

```bash
cd ~/coding_stuffs/discord-assitant
git checkout main && git pull --ff-only
git tag agent-v0.1.1            # provider-agent/pyproject.toml 의 version 과 일치
git push origin agent-v0.1.1
```

→ `agent-build` 워크플로가 Windows/macOS/Linux 바이너리·`SHA256SUMS.txt`·SBOM·attestation 과
함께 **렌더된 매니페스트**(`discord-ai-network-bot.rb`, `.json`, winget `*.yaml`)를 릴리스에 첨부한다.

> 무료 경로라 서명 시크릿이 없어도 릴리스가 통과한다(서명 게이트는 `REQUIRE_SIGNED_RELEASE=true`
> 변수일 때만 작동). 진행 상황: `gh run watch -R Hyeonjun0527/discord-assistant`

완료되면 자산 확인:
```bash
gh release view agent-v0.1.1 -R Hyeonjun0527/discord-assistant
```

---

## 1단계: Homebrew tap (macOS/Linux)

tap 저장소 이름은 **반드시 `homebrew-<탭이름>`** 형식이어야 한다(여기선 `homebrew-tap`).

```bash
# 1) tap 저장소 생성(공개)
gh repo create yeon-intergation-platform/homebrew-tap --public -d "Homebrew tap — discord-ai-network-bot"

# 2) 릴리스에서 렌더된 formula 내려받기
gh release download agent-v0.1.1 -R Hyeonjun0527/discord-assistant \
  -p discord-ai-network-bot.rb -D /tmp/tap

# 3) tap 에 커밋
git clone https://github.com/yeon-intergation-platform/homebrew-tap /tmp/homebrew-tap
mkdir -p /tmp/homebrew-tap/Formula
cp /tmp/tap/discord-ai-network-bot.rb /tmp/homebrew-tap/Formula/
cd /tmp/homebrew-tap
git add Formula/discord-ai-network-bot.rb
git commit -m "discord-ai-network-bot 0.1.1"
git push
```

**사용자 설치(검증 자동):**
```bash
brew tap yeon-intergation-platform/tap
brew install discord-ai-network-bot      # brew 가 sha256 자동 검증, 관리자 불필요
```
> macOS: brew 로 받은 파일은 격리(quarantine)가 안 붙어 Gatekeeper 경고 없이 실행된다(Apple $99 불필요).

---

## 2단계: Scoop bucket (Windows)

```bash
# 1) bucket 저장소 생성(공개)
gh repo create yeon-intergation-platform/scoop-bucket --public -d "Scoop bucket — discord-ai-network-bot"

# 2) 릴리스에서 렌더된 manifest 내려받기
gh release download agent-v0.1.1 -R Hyeonjun0527/discord-assistant \
  -p discord-ai-network-bot.json -D /tmp/sb

# 3) bucket 에 커밋(매니페스트는 bucket/ 폴더에)
git clone https://github.com/yeon-intergation-platform/scoop-bucket /tmp/scoop-bucket
mkdir -p /tmp/scoop-bucket/bucket
cp /tmp/sb/discord-ai-network-bot.json /tmp/scoop-bucket/bucket/
cd /tmp/scoop-bucket
git add bucket/discord-ai-network-bot.json
git commit -m "discord-ai-network-bot 0.1.1"
git push
```

**사용자 설치(검증 자동):**
```powershell
scoop bucket add nyassistant https://github.com/yeon-intergation-platform/scoop-bucket
scoop install discord-ai-network-bot     # scoop 이 hash 자동 검증, 관리자 불필요
```

---

## 3단계: 이후 릴리스 자동 갱신 — GitHub App (만료 없음)

org `yeon-intergation-platform` 은 장수명 PAT·deploy key 를 정책으로 막는다. 그래서 만료 없는
**GitHub App** 으로 tap/bucket push 권한을 준다(`publish-pkg-managers` 잡이 런타임에 설치 토큰 발급).

**한 번만:**
1. **App 생성**: `Org Settings → Developer settings → GitHub Apps → New GitHub App`
   (바로가기: `https://github.com/organizations/yeon-intergation-platform/settings/apps/new`)
   - GitHub App name: 예) `agent-pkg-publisher` (전역 고유)
   - Homepage URL: 아무 값(예: 메인 레포 URL)
   - **Webhook → Active 체크 해제**
   - **Repository permissions → Contents: Read and write** (이것만)
   - "Where can this GitHub App be installed?" → **Only on this account**
   - **Create GitHub App** → 표시되는 **App ID** 를 기록
2. **Private key 발급**: 같은 App 설정 하단 → *Generate a private key* → `.pem` 다운로드 →
   `~/keys/github-app.pem` 로 저장
3. **설치**: App 설정 → *Install App* → org 선택 → *Only select repositories* →
   `homebrew-tap`, `scoop-bucket` 선택 → Install
4. **시크릿 등록**(메인 레포):
   ```bash
   gh secret set PKG_APP_ID -R Hyeonjun0527/discord-assistant --body "<APP_ID>"
   gh secret set PKG_APP_PRIVATE_KEY -R Hyeonjun0527/discord-assistant < ~/keys/github-app.pem
   ```

이후 main 에 provider-agent 코드를 push 하면 `agent-autorelease` 가 버전을 올려 태그를 끊고,
`agent-build` 가 릴리스 + tap/bucket 갱신까지 **완전 무인**으로 수행한다. App 시크릿이 없으면
`publish-pkg-managers` 잡은 조용히 스킵된다(수동 갱신은 1·2단계 반복).

---

## 4단계: 동작 확인

- macOS: `brew update && brew install yeon-intergation-platform/tap/discord-ai-network-bot && discord-ai-network-bot --version`
- Windows: `scoop install discord-ai-network-bot; discord-ai-network-bot --version`

설치 페이지는 이미 `winget`/`scoop` 안내를 포함한다. Homebrew 안내를 페이지에 추가하고 싶으면
`central-server/src/main/resources/static/install.html` 의 macOS 패널에 `brew install` 한 줄을 더한다.

관련: [PACKAGE_MANAGERS.md](PACKAGE_MANAGERS.md), [RELEASE_SIGNING.md](RELEASE_SIGNING.md).
