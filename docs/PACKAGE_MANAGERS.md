# 패키지 매니저 배포 (사용자가 해시검증을 안 해도 되게)

수동 SHA256 검증은 어디까지나 **대체 수단**이다. 패키지 매니저로 설치하면 매니저가
다운로드 해시를 **자동 검증**하므로, 일반 사용자는 해시를 직접 비교할 필요가 없다.

| 경로 | 검증 주체 | 사용자 체감 |
|---|---|---|
| GitHub Release + `SHA256SUMS.txt` | 사용자(수동) | 해시 비교 필요 |
| **Homebrew / winget / Scoop** | 패키지 매니저(자동) | 한 줄 설치, 검증 자동 |
| 코드서명(Win/macOS) | OS(자동) | OS가 게시자/위변조 자동 확인 |

CI(`.github/workflows/agent-build.yml`)는 릴리스마다 `provider-agent/packaging/` 의 템플릿을
실제 `version`·`sha256` 으로 렌더해 릴리스 자산(`discord-ai-provider-agent.rb`,
`*.yaml`)으로 첨부한다.

## Homebrew (macOS/Linux)

`brew` 는 formula 의 `sha256` 과 실제 다운로드를 대조해 다르면 설치를 거부한다 → 자동 검증.

**1) 한 번만: tap 저장소 만들기**
- GitHub 에 `Hyeonjun0527/homebrew-tap` 저장소를 만든다(이름 규칙: `homebrew-<탭이름>`).
- `Formula/discord-ai-provider-agent.rb` 에 릴리스 자산의 렌더된 formula 를 커밋.
  (자동화하려면 tap 저장소에 푸시 권한이 있는 `HOMEBREW_TAP_TOKEN` 시크릿을 만들고,
  릴리스 후 렌더 자산을 그 저장소로 커밋하는 스텝을 추가.)

**2) 사용자 설치**
```bash
brew tap Hyeonjun0527/tap
brew install discord-ai-provider-agent      # 해시 자동 검증, 관리자 불필요
discord-ai-provider-agent --token <토큰> --relay-url wss://discord-ai.yeon.world/agent
```

## winget (Windows)

winget 은 매니페스트의 `InstallerSha256` 으로 자동 검증한다.

**1) 한 번만: microsoft/winget-pkgs 에 제출**
- 릴리스 자산의 렌더된 `provider-agent/packaging/winget/*.yaml`(version/installer/locale)을
  `microsoft/winget-pkgs` 에 PR 제출(`wingetcreate submit` 또는 수동 PR).
- 통과되면 `winget install Hyeonjun0527.DiscordAiProviderAgent` 로 전 세계 배포.

**2) 사용자 설치**
```powershell
winget install Hyeonjun0527.DiscordAiProviderAgent   # 해시 자동 검증, 관리자 불필요
```

## 요약: "사용자가 해시검증 안 하게" 하려면
1. **패키지 매니저 등록**(위) — 가장 직접적. 매니저가 검증.
2. **코드서명/공증**([RELEASE_SIGNING.md](RELEASE_SIGNING.md)) — OS가 검증.
3. 둘 다 갖추면 사용자는 `brew install` / `winget install` 한 줄이면 끝이고,
   무결성·게시자 검증은 매니저와 OS가 대신한다.
