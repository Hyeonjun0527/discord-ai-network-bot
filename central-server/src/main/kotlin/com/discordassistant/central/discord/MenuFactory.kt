package com.discordassistant.central.discord

import net.dv8tion.jda.api.entities.channel.ChannelType
import net.dv8tion.jda.api.entities.emoji.Emoji
import net.dv8tion.jda.api.interactions.components.buttons.Button
import net.dv8tion.jda.api.interactions.components.selections.EntitySelectMenu
import net.dv8tion.jda.api.interactions.components.selections.EntitySelectMenu.DefaultValue
import net.dv8tion.jda.api.interactions.components.selections.SelectOption
import net.dv8tion.jda.api.interactions.components.selections.StringSelectMenu

// Discord 버튼/Embed 에서 공통으로 쓰는 서비스 내부 심볼.
// Discord 가 이 기호들을 버튼 emoji 필드에서는 거부하므로, 라벨/텍스트로만 사용한다.
object MenuSymbols {
    const val ASK = "✦"
    const val PROVIDER = "❃"
    const val STATUS = "✡︎"
    const val HELP = "❆"
    const val SETTINGS = "❂"
}

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
    const val CHANNEL_BULK = "set:channel-bulk"
    const val CANCEL_SETTINGS = "set:cancel"
    const val SAVE_SETTINGS = "set:save"
    const val AUTO_APPROVE_SELECT = "set:autoapprove-select"

    // 프로바이더 참여 OS 선택(차수 19): 클릭하면 해당 OS 복붙 설치 명령을 보여준다. customId prefix "pjoin:".
    const val OS_PREFIX = "pjoin:"
    const val OS_MAC = "pjoin:mac"
    const val OS_WINDOWS = "pjoin:windows"
    const val OS_LINUX = "pjoin:linux"

    /** 프로바이더 참여: 설치할 컴퓨터(OS) 선택 버튼. 클릭 → 그 OS 의 복붙 명령. */
    fun osButtons(): List<Button> =
        listOf(
            Button.primary(OS_MAC, "macOS").withEmoji(Emoji.fromUnicode("🍎")),
            Button.primary(OS_WINDOWS, "Windows").withEmoji(Emoji.fromUnicode("🪟")),
            Button.secondary(OS_LINUX, "Linux").withEmoji(Emoji.fromUnicode("🐧")),
        )

    /** 설정 패널 상단 안내 텍스트(현재 상태 포함). */
    fun settingsText(
        autoApprove: Boolean,
        poolModels: List<String>,
        allowedChannelCount: Int,
    ): String {
        val sb = StringBuilder()
        sb.append(
            "⚙️ **서버 설정** — 언어·모델·채널·자동 승인을 고른 뒤 **설정 한 번에 저장**을 누르면 한 번에 적용됩니다.\n\n",
        )
        sb.append("• **언어**: 봇 응답 언어(한/영)\n")
        if (poolModels.isEmpty()) {
            sb.append("• **기본 모델**: 아직 연결된 프로바이더가 없어 *자동 선택*만 있어요. 프로바이더가 PC를 연결하면 그 PC의 모델들이 여기 채워집니다.\n")
        } else {
            sb.append("• **기본 모델**: 현재 풀 제공 모델 ${poolModels.size}종 — 그중 기본값 선택(또는 자동)\n")
        }
        if (allowedChannelCount == 0) {
            sb.append(
                "• **LLM 사용 허용 채널**: 현재 *모든 채널 허용*. 특정 채널만 허용하려면 채널 드롭다운에서 여러 채널을 체크한 뒤 저장\n",
            )
        } else {
            sb.append(
                "• **LLM 사용 허용 채널**: 현재 $allowedChannelCount 개 채널만 허용 — 현재 목록을 확인하고 드롭다운에서 한 번에 교체 가능\n",
            )
        }
        sb.append("• **자동 승인**: 현재 **${if (autoApprove) "켜짐(바로 참여)" else "꺼짐(관리자 승인 필요)"}**\n")
        return sb.toString()
    }

    /** 메인 패널 버튼(역할별). 일반 사용자는 질문/기여/상태/도움말, 관리자는 설정 추가. */
    fun mainButtons(isAdmin: Boolean): List<Button> {
        val base =
            listOf(
                Button.primary(ASK, "${MenuSymbols.ASK} 질문하기"),
                Button.secondary(PROVIDER, "${MenuSymbols.PROVIDER} 함께 도와주기"),
                Button.secondary(STATUS, "${MenuSymbols.STATUS} 내 상태"),
                Button.secondary(HELP, "${MenuSymbols.HELP} 도움말"),
            )
        return if (isAdmin) base + Button.secondary(SETTINGS, "${MenuSymbols.SETTINGS} 설정") else base
    }

    /** 언어 선택 드롭다운(ko/en). */
    fun languageSelect(current: String): StringSelectMenu =
        StringSelectMenu
            .create(LANG)
            .setPlaceholder("서버 언어 선택 (현재: $current)")
            .addOptions(
                SelectOption.of("한국어", "ko").withEmoji(Emoji.fromUnicode("🇰🇷")).withDefault(current == "ko"),
                SelectOption.of("English", "en").withEmoji(Emoji.fromUnicode("🇺🇸")).withDefault(current == "en"),
            ).build()

    /** 기본 모델 선택 드롭다운(풀 제공 모델 + 자동). 모델 없으면 자동만. */
    fun modelSelect(
        models: List<String>,
        current: String?,
    ): StringSelectMenu {
        val currentValue = current ?: "__auto__"
        val b =
            StringSelectMenu
                .create(MODEL)
                .setPlaceholder("기본 모델 선택 (현재: ${current ?: "자동 선택"})")
                .addOptions(
                    SelectOption
                        .of("자동 선택", "__auto__")
                        .withEmoji(Emoji.fromUnicode("🤖"))
                        .withDefault(currentValue == "__auto__"),
                )
        models
            .distinct()
            .sorted()
            .take(24)
            .forEach { b.addOptions(SelectOption.of(it, it).withDefault(it == currentValue)) } // 25개 한도(자동 1 + 24)
        return b.build()
    }

    /** 자동 승인 선택 드롭다운. 저장 전 대기값만 바꾼다. */
    fun autoApproveSelect(current: Boolean): StringSelectMenu =
        StringSelectMenu
            .create(AUTO_APPROVE_SELECT)
            .setPlaceholder("프로바이더 자동 승인 선택 (현재: ${if (current) "켜짐" else "꺼짐"})")
            .addOptions(
                SelectOption.of("켜짐 — 신청 즉시 참여", "true").withDefault(current),
                SelectOption.of("꺼짐 — 관리자 승인 필요", "false").withDefault(!current),
            ).build()

    /** 채널 허용 선택(서버 채널 엔티티 선택). 한 번 열어 여러 채널을 체크하고 저장 버튼으로 일괄 적용한다. */
    fun channelSelect(currentChannelIds: Collection<Long>): EntitySelectMenu {
        val defaults = currentChannelIds.distinct().take(25).map { DefaultValue.channel(it) }
        val placeholder =
            if (currentChannelIds.isEmpty()) {
                "사용 채널: 전체 허용 중 · 특정 채널만 쓰려면 여러 채널을 한 번에 선택"
            } else {
                "현재 ${currentChannelIds.size}개 사용 채널 선택됨 · 여러 채널을 체크해 한 번에 교체"
            }
        return EntitySelectMenu
            .create(CHANNEL, EntitySelectMenu.SelectTarget.CHANNEL)
            .setChannelTypes(ChannelType.TEXT, ChannelType.NEWS, ChannelType.FORUM, ChannelType.MEDIA)
            .setPlaceholder(placeholder)
            .setMinValues(0)
            .setMaxValues(25)
            .setDefaultValues(defaults)
            .build()
    }

    /** 설정 패널 저장/상태 버튼. 언어·모델·채널·자동승인을 고른 뒤 저장 하나로 반영한다. */
    fun settingsActionButtons(): List<Button> =
        listOf(
            Button.success(SAVE_SETTINGS, "언어·모델·채널 저장"),
            Button.secondary(CHANNEL_ALL, "모든 채널 허용 대기"),
            Button.secondary(CHANNEL_BULK, "채널 목록 붙여넣기"),
            Button.secondary(CANCEL_SETTINGS, "변경 취소"),
        )

    /** 채널 멘션/ID를 한 번에 붙여넣어 허용 채널 목록으로 바꾼다. 빈 입력/all/전체는 모든 채널 허용. */
    fun parseChannelIdsBulk(input: String): List<Long> {
        val trimmed = input.trim()
        if (trimmed.isBlank() || trimmed.equals("all", ignoreCase = true) || trimmed == "전체" || trimmed == "모든 채널") {
            return emptyList()
        }
        return Regex("\\d{5,}")
            .findAll(trimmed)
            .map { it.value.toLong() }
            .distinct()
            .take(25)
            .toList()
    }

    /** 슬림 도움말 — 핵심 3~5개만(판에서 보여줄 텍스트). */
    fun slimHelp(isAdmin: Boolean): String {
        val sb = StringBuilder()
        sb.append("**핵심만 빠르게** — 자세한 건 버튼으로!\n\n")
        sb.append("${MenuSymbols.ASK} **`/ask <질문>`** — 풀의 AI 에게 질문 (또는 위 `질문하기` 버튼)\n")
        sb.append("${MenuSymbols.PROVIDER} **`함께 도와주기`** 버튼 — 내 컴퓨터의 AI 로 커뮤니티 답변 돕기\n")
        sb.append("${MenuSymbols.STATUS} **`내 상태`** 버튼 — 내 사용량/기여 확인\n")
        sb.append("${MenuSymbols.HELP} **`/menu`** — 언제든 이 판을 다시 열기\n")
        if (isAdmin) {
            sb.append("${MenuSymbols.SETTINGS} **`설정`** 버튼(관리자) — 언어·기본모델·자동승인·허용채널을 드롭다운으로\n")
        }
        return sb.toString()
    }
}
