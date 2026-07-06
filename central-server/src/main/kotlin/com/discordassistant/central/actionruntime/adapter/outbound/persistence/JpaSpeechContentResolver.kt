package com.discordassistant.central.actionruntime.adapter.outbound.persistence

import com.discordassistant.central.actionruntime.application.port.out.SpeechContentResolver
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/**
 * [SpeechContentResolver] 의 JPA 구현(NEXA-P13-T017, outbound persistence 어댑터).
 *
 * 전송 명령이 운반하는 참조(speechPlanRef)를 `nexa_scheduled_action_content` 에서 실제 본문으로 푼다. 참조 행이
 * 없으면(미생성/만료/철회 purge) null 을 돌려준다 — 호출자([com.discordassistant.central.platform.discord.nexa
 * .DiscordMessageExecutor])는 전송하지 않고 우아하게 종결한다(원문 보호 경계 T003 일관).
 *
 * 순수성: application 포트 계약 ↔ entity 매핑만. 도메인은 이 어댑터를 모른다(헥사고날).
 */
@Component
class JpaSpeechContentResolver(
    private val content: ScheduledActionContentRepository,
) : SpeechContentResolver {
    @Transactional(readOnly = true)
    override fun resolve(speechPlanRef: String): String? = content.findByActionIdentity(speechPlanRef)?.content
}
