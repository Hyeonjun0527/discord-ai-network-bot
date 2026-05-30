package com.discordassistant.central.discord

import net.dv8tion.jda.api.entities.emoji.Emoji
import net.dv8tion.jda.api.interactions.components.buttons.Button
import net.dv8tion.jda.api.interactions.components.selections.EntitySelectMenu
import net.dv8tion.jda.api.interactions.components.selections.StringSelectMenu

/**
 * 인터랙티브 온보딩/설정 패널 컴포넌트 빌더(차수 13 UX 개편).
 *
 * 목표: 슬래시 명령 진입점을 `/menu` 하나로 줄이고, 온보딩·설정·도움말을 전부 버튼/드롭다운으로.
 * 첫 사용자가 명령을 외울 필요 없이 판에서 클릭만으로 끝낸다. 순수 빌더라 단위 테스트 가능.
 */
object MenuFactory {
    // 컴포넌트 ID(핸들러 라우팅 키)
    const val ASK = "menu:ask"
    const val PROVIDER = "menu:provider"
    const val HELP = "menu:help"
    const val SETTINGS = "menu:settings"
    const val STATUS = "menu:status"
    const val AUTO_APPROVE = "set:autoapprove" // (구) 토글 — 하위호환
    const val AUTO_APPROVE_ON = "set:aa-on"
    const val AUTO_APPROVE_OFF = "set:aa-off"
    const val LANG = "set:lang"
    const val MODEL = "set:model"
    const val CHANNEL = "set:channel"
    const val CHANNEL_ALL = "set:channel-all"

    /** 설정 패널 상단 안내 텍스트(현재 상태 포함). */
    fun settingsText(
        autoApprove: Boolean,
        poolModels: List<String>,
        allowedChannelCount: Int,
    ): String {
        val sb = StringBuilder()
        sb.append("⚙️ **서버 설정** — 아래 메뉴/버튼으로 바로 적용됩니다.\n\n")
        sb.append("• **언어**: 봇 응답 언어(한/영)\n")
        if (poolModels.isEmpty()) {
            sb.append("• **기본 모델**: 아직 연결된 프로바이더가 없어 *자동 선택*만 있어요. 프로바이더가 PC를 연결하면 그 PC의 모델들이 여기 채워집니다.\n")
        } else {
            sb.append("• **기본 모델**: 현재 풀 제공 모델 ${poolModels.size}종 — 그중 기본값 선택(또는 자동)\n")
        }
        if (allowedChannelCount == 0) {
            sb.append("• **사용 채널**: 현재 *모든 채널 허용*. 특정 채널만 허용하려면 아래에서 채널 선택\n")
        } else {
            sb.append("• **사용 채널**: 현재 $allowedChannelCount 개 채널만 허용 — '모든 채널 허용'으로 제한 해제 가능\n")
        }
        sb.append("• **자동 승인**: 현재 **${if (autoApprove) "켜짐(바로 참여)" else "꺼짐(관리자 승인 필요)"}**\n")
        return sb.toString()
    }

    /** 메인 패널 버튼(역할별). 일반 사용자는 질문/기여/상태/도움말, 관리자는 설정 추가. */
    fun mainButtons(isAdmin: Boolean): List<Button> {
        val base =
            listOf(
                Button.primary(ASK, "질문하기").withEmoji(Emoji.fromUnicode("💬")),
                Button.success(PROVIDER, "내 PC 기여").withEmoji(Emoji.fromUnicode("🖥️")),
                Button.secondary(STATUS, "내 상태").withEmoji(Emoji.fromUnicode("📊")),
                Button.secondary(HELP, "도움말").withEmoji(Emoji.fromUnicode("❓")),
            )
        return if (isAdmin) base + Button.danger(SETTINGS, "설정").withEmoji(Emoji.fromUnicode("⚙️")) else base
    }

    /** 언어 선택 드롭다운(ko/en). */
    fun languageSelect(current: String): StringSelectMenu =
        StringSelectMenu
            .create(LANG)
            .setPlaceholder("서버 언어 선택 (현재: $current)")
            .addOption("한국어", "ko", Emoji.fromUnicode("🇰🇷"))
            .addOption("English", "en", Emoji.fromUnicode("🇺🇸"))
            .build()

    /** 기본 모델 선택 드롭다운(풀 제공 모델 + 자동). 모델 없으면 자동만. */
    fun modelSelect(models: List<String>): StringSelectMenu {
        val b =
            StringSelectMenu
                .create(MODEL)
                .setPlaceholder("기본 모델 선택")
                .addOption("자동 선택", "__auto__", Emoji.fromUnicode("🤖"))
        models
            .distinct()
            .sorted()
            .take(24)
            .forEach { b.addOption(it, it) } // 25개 한도(자동 1 + 24)
        return b.build()
    }

    /** 채널 허용 선택(서버 채널 엔티티 선택). */
    fun channelSelect(): EntitySelectMenu =
        EntitySelectMenu
            .create(CHANNEL, EntitySelectMenu.SelectTarget.CHANNEL)
            .setPlaceholder("LLM 사용 허용 채널 선택")
            .build()

    /** 슬림 도움말 — 핵심 3~5개만(판에서 보여줄 텍스트). */
    fun slimHelp(isAdmin: Boolean): String {
        val sb = StringBuilder()
        sb.append("**핵심만 빠르게** — 자세한 건 버튼으로!\n\n")
        sb.append("💬 **`/ask <질문>`** — 풀의 AI 에게 질문 (또는 위 `질문하기` 버튼)\n")
        sb.append("🖥️ **`내 PC 기여`** 버튼 — 내 Ollama 를 풀에 연결(프로바이더)\n")
        sb.append("📊 **`내 상태`** 버튼 — 내 사용량/기여 확인\n")
        sb.append("🧭 **`/menu`** — 언제든 이 판을 다시 열기\n")
        if (isAdmin) {
            sb.append("⚙️ **`설정`** 버튼(관리자) — 언어·기본모델·자동승인·허용채널을 드롭다운으로\n")
        }
        return sb.toString()
    }
}
