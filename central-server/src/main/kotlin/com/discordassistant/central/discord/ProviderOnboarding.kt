package com.discordassistant.central.discord

/**
 * 프로바이더 온보딩 안내문(차수 12/13 UX). 승인 시 봇이 토큰만 주는 게 아니라
 * "이 토큰으로 무엇을 하면 되는지"를 단계별로 알려준다. 순수 함수라 단위 테스트 가능.
 * Discord 2000자 제한 안에서 핵심만.
 */
object ProviderOnboarding {
    // 단일 실행파일 다운로드 — 사용자가 신뢰할 수 있게 GitHub Releases 자산으로 안내한다.
    private const val DL = "https://github.com/Hyeonjun0527/discord-ai-network-bot/releases/latest/download"

    // 설치 랜딩 페이지(차수 19) — OS별 복붙 명령 + 소스 코드 버튼. 링크만 던지지 않고 정제된 가이드로 안내.
    const val INSTALL_PAGE = "https://discord-ai.yeon.world/install"

    /**
     * OS 선택(버튼) 후 보여줄 **복붙용 설치 명령**(차수 19 UX). Ollama 설치 → 모델 받기 → 에이전트 실행까지 한 블록.
     * macOS/Linux 는 터미널, Windows 는 PowerShell(관리자) 기준. 토큰은 ⏳ 10분·1회용.
     */
    fun installCommand(
        os: String,
        token: String,
        relayUrl: String,
    ): String {
        val relay = relayUrl.ifBlank { "wss://<관리자에게 문의>/agent" }
        val note =
            "\n토큰은 ⏳ **10분·1회용**. 연결되면 `/내상태`(provider-status)로 확인하세요. " +
                "📄 웹 가이드·GitHub Release: $INSTALL_PAGE · **민감정보 입력 금지.**" +
                "\n🖼️ (선택) **이미지 생성도 제공**하려면 로컬 Stable Diffusion(A1111 등)을 켜고 " +
                "에이전트에 `--enable-image` 를 추가하세요. 그러면 `/그림` 요청을 받을 수 있어요. " +
                "누구나 이미지 프로바이더가 될 수 있습니다."
        // mac/windows 는 **데스크톱 GUI 앱**(맥의 ‘냥시스턴트’ 앱과 동일)을 패키지 매니저로 설치하고,
        // 앱을 열어 ‘디스코드 로그인’ 또는 토큰 붙여넣기로 연결한다. linux 는 GUI 빌드가 없어 CLI 사용.
        return when (os.lowercase()) {
            "mac", "macos" ->
                "🍎 **macOS** — 앱 설치(터미널: `⌘ Space` → `Terminal`):\n" +
                    "```bash\n" +
                    "brew install ollama && brew services start ollama && ollama pull llama3.1:8b\n" +
                    "brew install --cask yeon-intergation-platform/tap/nyassistant\n" +
                    "```\n" +
                    "설치되면 **응용 프로그램 → ‘냥시스턴트’** 앱을 열고, **‘디스코드 로그인’** 으로 이 서버를 고르거나 " +
                    "**‘고급 · 토큰 직접 입력’** 에 아래 토큰을 붙여넣어 연결하세요:\n" +
                    "```\n$token\n```" + note
            "windows", "win" ->
                "🪟 **Windows** — 앱 설치(PowerShell: `Win + X` → 터미널):\n" +
                    "```powershell\n" +
                    "winget install --id Ollama.Ollama -e --accept-source-agreements\n" +
                    "ollama pull llama3.1:8b\n" +
                    "winget install --id Nyassistant.DiscordAiNetworkBot -e --accept-source-agreements\n" +
                    "```\n" +
                    "설치되면 **‘냥시스턴트’ 앱**(시작 메뉴)을 열고, **‘디스코드 로그인’** 또는 **‘고급 · 토큰 직접 입력’** 에 " +
                    "아래 토큰을 붙여넣어 연결하세요:\n" +
                    "```\n$token\n```" + note
            "linux" ->
                "🐧 **Linux** — 터미널(`Ctrl + Alt + T` / 또는 앱 메뉴에서 Terminal 검색)에서 CLI 에이전트 설치" +
                    "(GUI 앱은 mac/Windows 전용):\n" +
                    "```bash\n" +
                    "curl -fsSL https://ollama.com/install.sh | sh\n" +
                    "ollama pull llama3.1:8b\n" +
                    "curl -L -o discord-ai-network-bot-linux $DL/discord-ai-network-bot-linux " +
                    "&& chmod +x discord-ai-network-bot-linux\n" +
                    "./discord-ai-network-bot-linux --token $token --relay-url $relay\n" +
                    "```" + note
            else -> message(token, relayUrl) // 알 수 없는 OS → 전체 안내로 폴백
        }
    }

    fun message(
        token: String,
        relayUrl: String,
    ): String {
        val sb = StringBuilder()
        sb.append("🖥️ **프로바이더로 승인되었습니다!** 내 PC 로컬 AI 를 풀에 연결하기:\n\n")
        sb.append("**1) ‘냥시스턴트’ 앱 설치** → $INSTALL_PAGE\n")
        sb.append(
            "   macOS `brew install --cask yeon-intergation-platform/tap/nyassistant` · " +
                "Windows `winget install Nyassistant.DiscordAiNetworkBot`.\n\n",
        )
        sb.append("**2) 앱을 열고** ‘디스코드 로그인’ 또는 ‘고급 · 토큰 직접 입력’에 아래 토큰을 넣어 연결 (⏳ **10분·1회용**):\n")
        sb.append("```\n$token\n```\n")
        sb.append("연결되면 `/내상태` 로 확인. ")
        if (relayUrl.isBlank()) {
            sb.append("⚠️ 연결 주소(`relay-url`)가 설정되지 않았어요 — 관리자에게 문의하세요. ")
        }
        sb.append("비밀번호·API 키 등 **민감정보는 절대 입력하지 마세요.**")
        return sb.toString()
    }
}
