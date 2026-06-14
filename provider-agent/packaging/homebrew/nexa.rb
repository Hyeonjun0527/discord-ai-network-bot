# Homebrew **Cask** (tap 용) — 데스크톱 GUI 앱(Nexa.app). brew 가 url 의 sha256 을 자동 검증하고
# .app 을 응용 프로그램에 설치한다(관리자 불필요, brew 설치라 Gatekeeper 격리 미부여). winget(Windows GUI)과
# 동일하게 **맥에서 보이는 그 데스크톱 앱**을 설치한다. CLI/헤드리스가 필요하면 formula(nexa-agent).
#
# 사용자 설치:
#   brew install --cask yeon-intergation-platform/tap/nexa
#
# 릴리스마다 CI(agent-build.yml)가 version·sha256 을 채워 tap 저장소(Casks/)로 보낸다.
cask "nexa" do
  version "__VERSION__"
  sha256 "__MACOS_APP_SHA256__"

  url "https://github.com/yeon-intergation-platform/nexa-releases/releases/download/agent-v#{version}/nexa-macos.zip"
  name "Nexa"
  desc "Nexa 데스크톱 앱 — 내 로컬 AI모델을 디스코드 서버/채널에 공유"
  homepage "https://github.com/yeon-intergation-platform/nexa-releases"

  app "Nexa.app"

  zap trash: [
    "~/.config/nexa",
  ]
end
