package com.discordassistant.central.discord

import com.discordassistant.central.domain.ProviderState
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.entities.MessageEmbed
import net.dv8tion.jda.api.interactions.DiscordLocale
import java.awt.Color

/**
 * 응답 Embed 고도화(차수 13 #156). 상태별 색상 badge 로 가독성을 높인다.
 * 순수 빌더라 JDA 연결 없이 단위 테스트 가능.
 */
object EmbedFactory {
    fun stateColor(state: ProviderState): Color =
        when (state) {
            ProviderState.ONLINE_IDLE, ProviderState.ONLINE_BUSY -> Color(0x57F287) // green
            ProviderState.LIMITED, ProviderState.PAUSED -> Color(0xFEE75C) // yellow
            ProviderState.UNHEALTHY, ProviderState.OFFLINE -> Color(0xED4245) // red
            else -> Color(0x5865F2) // blurple(중립)
        }

    /** 프로바이더 상태 Embed: 상태 색상 + 처리중/잔여/실패. */
    fun providerStatus(
        providerId: Long,
        state: ProviderState,
        inFlight: Int,
        failures: Int,
    ): MessageEmbed =
        EmbedBuilder()
            .setTitle("프로바이더 상태")
            .setColor(stateColor(state))
            .addField("상태", state.name, true)
            .addField("처리중", inFlight.toString(), true)
            .addField("실패", failures.toString(), true)
            .setFooter("provider:$providerId")
            .build()

    /** 풀 요약 Embed. */
    fun poolSummary(
        active: Int,
        models: Int,
        inFlight: Int,
    ): MessageEmbed =
        EmbedBuilder()
            .setTitle("Provider Pool 요약")
            .setColor(if (active > 0) Color(0x57F287) else Color(0xED4245))
            .addField("활성 프로바이더", "${active}명", true)
            .addField("제공 모델", "${models}종", true)
            .addField("처리중", "${inFlight}건", true)
            .build()

    private val BLURPLE = Color(0x5865F2)
    private val DEEP_INDIGO = Color(0x2F3BFF)
    const val MENU_HERO_IMAGE_URL = "https://discord-ai.yeon.world/assets/nyassistant-menu-hero.png"

    /** 시작 메뉴 Embed. 실제 Discord 버튼은 Embed 아래에 붙지만, 패널 안에도 같은 메뉴 구조를 보여준다. */
    fun mainMenuEmbed(isAdmin: Boolean): MessageEmbed {
        val b =
            EmbedBuilder()
                .setColor(DEEP_INDIGO)
                .setTitle("냥시스턴트 메뉴")
                .setDescription(
                    "AI에게 바로 질문하거나, 내 컴퓨터의 AI로 커뮤니티 답변을 함께 도울 수 있어요.\n" +
                        "아래 메뉴를 보고 원하는 버튼을 선택해주세요.",
                ).setImage(MENU_HERO_IMAGE_URL)
                .addField("${MenuSymbols.ASK} 질문하기", "AI에게 바로 물어보기", true)
                .addField("${MenuSymbols.PROVIDER} 함께 도와주기", "내 컴퓨터의 AI로 답변 참여", true)
                .addField("${MenuSymbols.STATUS} 내 상태", "연결 상태와 기여 확인", true)
                .addField("${MenuSymbols.HELP} 도움말", "사용 방법과 자주 묻는 상황", true)
        if (isAdmin) {
            b.addField("${MenuSymbols.SETTINGS} 설정", "서버 언어·채널·승인 관리", true)
        }
        return b.setFooter("언제든 /menu 로 다시 열 수 있어요.").build()
    }

    /**
     * 도움말 패널 Embed(역할별 섹션). 관리자에게만 관리자 필드 노출.
     * 명령 이름은 보는 사람의 클라이언트 로케일(locale)로 표시 — 슬래시 메뉴에 보이는 이름과 일치(차수 19 UX).
     * 예) 한국어 클라이언트: `/질문` `/메뉴` `/서버기본값`, 영어: `/ask` `/menu`.
     */
    fun helpEmbed(
        isAdmin: Boolean,
        locale: DiscordLocale = DiscordLocale.KOREAN,
    ): MessageEmbed {
        fun c(base: String) = "`/${CommandLoc.localName(base, locale)}`"
        val b =
            EmbedBuilder()
                .setColor(BLURPLE)
                .setTitle("${MenuSymbols.ASK} 커뮤니티 로컬 AI Provider Pool")
                .setDescription(
                    "커뮤니티 멤버들의 PC 로컬 LLM 을 모아 **공정하게 나눠 쓰는** 봇입니다(금전 거래 아님).\n" +
                        "${c("menu")} 로 언제든 시작 패널을 열 수 있어요.",
                ).addField(
                    "${MenuSymbols.ASK} 유저",
                    "${c("ask")} `<질문>` — 풀의 누군가의 PC AI 로 답변\n" +
                        "${c("models")} ${c("catalog")} — 사용 가능한 모델 수준·목록\n" +
                        "${c("my-usage")} ${c("contributions")} — 내 사용량 · 기여 리더보드",
                    false,
                ).addField(
                    "${MenuSymbols.PROVIDER} 프로바이더 (내 컴퓨터의 AI로 함께 도와주기)",
                    "${c("provider-join")} — 참여 신청(승인 후 토큰→에이전트 실행)\n" +
                        "${c("provider-status")} ${c("provider-pause")} ${c("provider-resume")} — 상태·가용성\n" +
                        "${c("provider-schedule")} — 가용 시간대 설정",
                    false,
                )
        if (isAdmin) {
            b.addField(
                "${MenuSymbols.SETTINGS} 관리자",
                "${c("llm-settings")} — 설정 패널(언어·모델·채널·자동승인)\n" +
                    "${c("provider-approve")} ${c("providers")} ${c("fairness")} — 승인·현황·공정성\n" +
                    "${c("llm-channel-profile")} — 이 채널 AI 이름·아이콘·말투 설정\n" +
                    "${c("llm-allow-channel")} ${c("llm-block")} — 채널·차단 정책",
                false,
            )
        }
        b.setFooter("민감정보(비밀번호·API 키 등)는 절대 입력하지 마세요.")
        return b.build()
    }

    /** 설정 패널 Embed(현재 상태를 필드로). 컴포넌트(드롭다운/버튼)는 메시지에 함께 첨부. */
    fun settingsEmbed(
        language: String,
        defaultModel: String?,
        poolModelCount: Int,
        allowedChannelCount: Int,
        autoApprove: Boolean,
        allowedChannelText: String? = null,
    ): MessageEmbed =
        EmbedBuilder()
            .setColor(BLURPLE)
            .setTitle("⚙️ 서버 설정")
            .setDescription("아래 **드롭다운/버튼**으로 바로 적용됩니다. 현재 설정:")
            .addField("🌐 언어", if (language.equals("en", true)) "English" else "한국어", true)
            .addField(
                "🧠 기본 모델",
                defaultModel ?: if (poolModelCount == 0) "자동 (프로바이더 연결 시 선택지 생김)" else "자동 선택",
                true,
            ).addField(
                "#️⃣ 사용 채널",
                allowedChannelText ?: if (allowedChannelCount == 0) "모든 채널 허용" else "${allowedChannelCount}개 채널만",
                true,
            ).addField(
                "✅ 프로바이더 자동 승인",
                if (autoApprove) "켜짐 — 신청 즉시 참여" else "꺼짐 — 관리자 승인 필요",
                true,
            ).setFooter("선택/클릭하면 즉시 저장됩니다.")
            .build()
}
