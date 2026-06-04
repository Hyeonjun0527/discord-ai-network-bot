package com.discordassistant.central.discord

import com.discordassistant.central.domain.InstallGuide

/**
 * 프로바이더 온보딩 안내문(차수 12/13 UX). 승인 시 봇이 토큰만 주는 게 아니라
 * "이 토큰으로 무엇을 하면 되는지"를 단계별로 알려준다. 순수 함수라 단위 테스트 가능.
 *
 * 설치 명령/앱 이름/연결 방법의 **SSOT 는 [InstallGuide]** — 웹 랜딩(`/install`)과 같은 원천을 읽어
 * 두 화면이 드리프트하지 않는다. 여기선 디스코드 마크다운으로 렌더링만 한다(Discord 2000자 한도).
 */
object ProviderOnboarding {
    const val INSTALL_PAGE = InstallGuide.INSTALL_PAGE

    /**
     * OS 선택(버튼) 후 보여줄 **복붙용 설치 명령**. Ollama 설치 → GUI 앱(냥시스턴트) 설치 → 앱에서 연결.
     * mac/Windows 만 지원(GUI 앱 배포 대상). 알 수 없는 OS 는 전체 안내로 폴백한다. 토큰은 ⏳ 10분·1회용.
     */
    fun installCommand(
        os: String,
        token: String,
        relayUrl: String,
    ): String {
        val g = InstallGuide.forOs(os) ?: return message(token, relayUrl)
        return "${g.emoji} **${g.label}** — 앱 설치(${g.terminalHint}):\n" +
            "```${g.shellLang}\n" +
            g.codeLines.joinToString("\n") + "\n" +
            "```\n" +
            "${g.connect}:\n" +
            "```\n$token\n```" + note()
    }

    private fun note(): String =
        "\n토큰은 ⏳ **10분·1회용**. 연결되면 `/내상태`(provider-status)로 확인하세요. " +
            "📄 웹 가이드: $INSTALL_PAGE · **민감정보 입력 금지.**" +
            "\n🖼️ (선택) **이미지 생성도 제공**하려면 앱 설정에서 로컬 Stable Diffusion(A1111 등)을 켜세요. " +
            "그러면 `/그림` 요청을 받을 수 있어요. 누구나 이미지 프로바이더가 될 수 있습니다."

    fun message(
        token: String,
        relayUrl: String,
    ): String {
        val sb = StringBuilder()
        sb.append("🖥️ **프로바이더로 승인되었습니다!** 내 PC 로컬 AI 를 풀에 연결하기:\n\n")
        sb.append("**1) ‘${InstallGuide.APP_NAME}’ 앱 설치** → $INSTALL_PAGE\n")
        sb.append(
            "   macOS `${InstallGuide.MAC.appInstall}` · " +
                "Windows `${InstallGuide.WIN.appInstall}`.\n\n",
        )
        sb.append(
            "**2) 앱을 열고** ‘디스코드 로그인’ 또는 ‘고급 · 토큰 직접 입력’에 아래 토큰을 넣어 연결 " +
                "(⏳ **10분·1회용**):\n",
        )
        sb.append("```\n$token\n```\n")
        sb.append("연결되면 `/내상태` 로 확인. ")
        if (relayUrl.isBlank()) {
            sb.append("⚠️ 연결 주소(`relay-url`)가 설정되지 않았어요 — 관리자에게 문의하세요. ")
        }
        sb.append("비밀번호·API 키 등 **민감정보는 절대 입력하지 마세요.**")
        return sb.toString()
    }
}
