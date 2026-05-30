package com.discordassistant.central.discord

import com.discordassistant.central.domain.ProviderState
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.entities.MessageEmbed
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

    /** 도움말 패널 Embed(역할별 섹션). 관리자에게만 관리자 필드 노출. */
    fun helpEmbed(isAdmin: Boolean): MessageEmbed {
        val b =
            EmbedBuilder()
                .setColor(BLURPLE)
                .setTitle("🤖 커뮤니티 로컬 AI Provider Pool")
                .setDescription("커뮤니티 멤버들의 PC 로컬 LLM 을 모아 **공정하게 나눠 쓰는** 봇입니다(금전 거래 아님).\n`/menu` 로 언제든 시작 패널을 열 수 있어요.")
                .addField(
                    "💬 유저",
                    "`/ask <질문>` — 풀의 누군가의 PC AI 로 답변\n" +
                        "`/models` `/catalog` — 사용 가능한 모델 수준·목록\n" +
                        "`/my-usage` `/contributions` — 내 사용량 · 기여 리더보드",
                    false,
                ).addField(
                    "🖥️ 프로바이더 (내 PC 를 풀에 기여)",
                    "`/provider-join` — 참여 신청(승인 후 토큰→에이전트 실행)\n" +
                        "`/provider-status` `/provider-pause` `/provider-resume` — 상태·가용성\n" +
                        "`/provider-schedule` — 가용 시간대 설정",
                    false,
                )
        if (isAdmin) {
            b.addField(
                "⚙️ 관리자",
                "`/llm-settings` — 설정 패널(언어·모델·채널·자동승인)\n" +
                    "`/approve-provider` `/providers` `/fairness` — 승인·현황·공정성\n" +
                    "`/llm-allow-channel` `/llm-block` — 채널·차단 정책",
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
                if (allowedChannelCount == 0) "모든 채널 허용" else "${allowedChannelCount}개 채널만",
                true,
            ).addField(
                "✅ 프로바이더 자동 승인",
                if (autoApprove) "켜짐 — 신청 즉시 참여" else "꺼짐 — 관리자 승인 필요",
                true,
            ).setFooter("선택/클릭하면 즉시 저장됩니다.")
            .build()
}
