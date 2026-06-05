package com.discordassistant.central.onboarding.domain.model

/**
 * 프로바이더 설치 가이드의 **단일 진실 원천(SSOT)**.
 *
 * 디스코드 슬래시 안내(`ProviderOnboarding`)와 웹 랜딩(`/install`)이 **둘 다 이 객체만** 읽는다.
 * 예전엔 양쪽이 따로 하드코딩돼 드리프트했다 — 이제 설치 명령/앱 이름/연결 안내를 여기서만 바꾸면
 * 두 화면이 항상 일치한다. 도메인 계층이라 어떤 바깥 레이어에도 의존하지 않는 순수 값이다.
 *
 * 지원 OS 는 GUI 데스크톱 앱(NEXA)이 배포되는 **mac/Windows 뿐**이다(Linux 가이드/다운로드 폐기).
 */
object InstallGuide {
    /** 데스크톱 GUI 앱 이름(맥 `.app`·Windows 시작 메뉴에 동일하게 보이는 이름). */
    const val APP_NAME = "NEXA"

    /** 정제된 설치 랜딩 페이지(OS 탭 + 복붙 명령). 디스코드 안내에서도 이 링크로 보낸다. */
    const val INSTALL_PAGE = "https://discord-ai.yeon.world/install"

    /**
     * 한 OS 의 설치 가이드. `codeLines` 는 복붙용 셸 블록(주석은 `#` 로 시작), `appInstall` 은
     * 패키지 매니저로 GUI 앱을 까는 한 줄(요약 안내용), `connect` 는 앱을 열어 연결하는 방법이다.
     */
    data class OsGuide(
        val key: String,
        val label: String,
        val emoji: String,
        val shellName: String,
        val shellLang: String,
        val terminalHint: String,
        val codeLines: List<String>,
        val appInstall: String,
        val connect: String,
    )

    private const val MAC_CASK = "brew install --cask yeon-intergation-platform/tap/nexa"
    private const val WIN_PKG_ID = "Nexa.Nexa"

    val MAC =
        OsGuide(
            key = "mac",
            label = "macOS",
            emoji = "🍎",
            shellName = "terminal — zsh",
            shellLang = "bash",
            terminalHint = "⌘ Space → Terminal 입력 → Enter (또는 응용 프로그램 → 유틸리티 → 터미널)",
            codeLines =
                listOf(
                    "# 1) Ollama 설치 + 기본 모델 exaone3.5:7.8b (관리자 권한 불필요)",
                    "brew install ollama",
                    "brew services start ollama",
                    "ollama pull exaone3.5:7.8b",
                    "# 2) NEXA 데스크톱 앱 설치 (brew 가 sha256 자동 검증, 관리자 불필요)",
                    MAC_CASK,
                ),
            appInstall = MAC_CASK,
            connect =
                "응용 프로그램에서 ‘$APP_NAME’ 앱을 열고 → ‘디스코드 로그인’ 으로 서버를 고르거나 " +
                    "‘고급 · 토큰 직접 입력’ 에 토큰을 붙여넣어 연결",
        )

    val WIN =
        OsGuide(
            key = "win",
            label = "Windows",
            emoji = "🪟",
            shellName = "PowerShell",
            shellLang = "powershell",
            terminalHint = "시작 메뉴에서 PowerShell 검색 → Enter, 또는 Win + X → 터미널 (관리자 권한 불필요)",
            codeLines =
                listOf(
                    "# 1) Ollama 설치 + 기본 모델 exaone3.5:7.8b (관리자 권한 불필요)",
                    "winget install --id Ollama.Ollama -e --accept-source-agreements",
                    "ollama pull exaone3.5:7.8b",
                    "# 2) NEXA 데스크톱 앱 설치 (winget 가 sha256 자동 검증, 관리자 불필요)",
                    "winget install --id $WIN_PKG_ID -e --accept-source-agreements",
                ),
            appInstall = "winget install $WIN_PKG_ID",
            connect =
                "시작 메뉴에서 ‘$APP_NAME’ 앱을 열고 → ‘디스코드 로그인’ 으로 서버를 고르거나 " +
                    "‘고급 · 토큰 직접 입력’ 에 토큰을 붙여넣어 연결",
        )

    /** 지원 OS 목록(가이드/웹 탭 순서). Linux 는 GUI 앱이 없어 미지원. */
    val OSES = listOf(MAC, WIN)

    /** 사용자가 고른 OS 문자열을 정규화해 가이드를 찾는다(mac/macos, win/windows). 미지원이면 null. */
    fun forOs(os: String): OsGuide? =
        when (os.trim().lowercase()) {
            "mac", "macos", "osx", "darwin" -> MAC
            "win", "windows" -> WIN
            else -> null
        }
}
