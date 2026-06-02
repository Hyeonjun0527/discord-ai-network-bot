# 패키지 매니저 셋업 가이드 (Homebrew tap · Scoop bucket) — $0

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
함께 **렌더된 매니페스트**(`discord-ai-provider-agent.rb`, `.json`, winget `*.yaml`)를 릴리스에 첨부한다.

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
gh repo create Hyeonjun0527/homebrew-tap --public -d "Homebrew tap — discord-ai-provider-agent"

# 2) 릴리스에서 렌더된 formula 내려받기
gh release download agent-v0.1.1 -R Hyeonjun0527/discord-assistant \
  -p discord-ai-provider-agent.rb -D /tmp/tap

# 3) tap 에 커밋
git clone https://github.com/Hyeonjun0527/homebrew-tap /tmp/homebrew-tap
mkdir -p /tmp/homebrew-tap/Formula
cp /tmp/tap/discord-ai-provider-agent.rb /tmp/homebrew-tap/Formula/
cd /tmp/homebrew-tap
git add Formula/discord-ai-provider-agent.rb
git commit -m "discord-ai-provider-agent 0.1.1"
git push
```

**사용자 설치(검증 자동):**
```bash
brew tap Hyeonjun0527/tap
brew install discord-ai-provider-agent      # brew 가 sha256 자동 검증, 관리자 불필요
```
> macOS: brew 로 받은 파일은 격리(quarantine)가 안 붙어 Gatekeeper 경고 없이 실행된다(Apple $99 불필요).

---

## 2단계: Scoop bucket (Windows)

```bash
# 1) bucket 저장소 생성(공개)
gh repo create Hyeonjun0527/scoop-bucket --public -d "Scoop bucket — discord-ai-provider-agent"

# 2) 릴리스에서 렌더된 manifest 내려받기
gh release download agent-v0.1.1 -R Hyeonjun0527/discord-assistant \
  -p discord-ai-provider-agent.json -D /tmp/sb

# 3) bucket 에 커밋(매니페스트는 bucket/ 폴더에)
git clone https://github.com/Hyeonjun0527/scoop-bucket /tmp/scoop-bucket
mkdir -p /tmp/scoop-bucket/bucket
cp /tmp/sb/discord-ai-provider-agent.json /tmp/scoop-bucket/bucket/
cd /tmp/scoop-bucket
git add bucket/discord-ai-provider-agent.json
git commit -m "discord-ai-provider-agent 0.1.1"
git push
```

**사용자 설치(검증 자동):**
```powershell
scoop bucket add nyassistant https://github.com/Hyeonjun0527/scoop-bucket
scoop install discord-ai-provider-agent     # scoop 이 hash 자동 검증, 관리자 불필요
```

---

## 3단계: 이후 릴리스 자동 갱신 (선택, 강력 추천)

위는 첫 등록만 수동이다. PAT 하나만 등록하면 **다음 릴리스부터 tap/bucket 이 자동 갱신**된다
(`agent-build.yml` 의 `publish-pkg-managers` 잡).

```bash
# 1) 두 레포에 push 가능한 토큰 발급
#    - 권장: Fine-grained PAT — Repository access: homebrew-tap, scoop-bucket 선택,
#      Permissions → Contents: Read and write.
#    - 발급: https://github.com/settings/tokens?type=beta
# 2) 메인 레포에 시크릿으로 등록
gh secret set PKG_PUSH_TOKEN -R Hyeonjun0527/discord-assistant
#   (프롬프트에 PAT 붙여넣기)
```

이후 `git tag agent-vX.Y.Z && git push origin agent-vX.Y.Z` 하면 릴리스 + tap/bucket 갱신이 끝까지 자동.
PAT 가 없으면 그 잡은 조용히 스킵된다(수동 갱신은 1·2단계 반복).

---

## 4단계: 동작 확인

- macOS: `brew update && brew install Hyeonjun0527/tap/discord-ai-provider-agent && discord-ai-provider-agent --version`
- Windows: `scoop install discord-ai-provider-agent; discord-ai-provider-agent --version`

설치 페이지는 이미 `winget`/`scoop` 안내를 포함한다. Homebrew 안내를 페이지에 추가하고 싶으면
`central-server/src/main/resources/static/install.html` 의 macOS 패널에 `brew install` 한 줄을 더한다.

관련: [PACKAGE_MANAGERS.md](PACKAGE_MANAGERS.md), [RELEASE_SIGNING.md](RELEASE_SIGNING.md).
