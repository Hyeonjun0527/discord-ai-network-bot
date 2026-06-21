package com.discordassistant.central.conversation.adapter.outbound.persistence

import com.discordassistant.central.conversation.application.port.out.ConsentPolicyPort
import com.discordassistant.central.conversation.domain.model.ConsentDecision
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * 기본 [ConsentPolicyPort] 어댑터(NEXA-P03 보조). 실제 동의 합성 어댑터(길드 활성/옵트아웃/채널 스코프 조회)는
 * 후속 task 에서 붙는다 — 그 전까지 [com.discordassistant.central.conversation.application.ingest.IngestDiscordEventService]
 * 가 wiring 되도록 **fail-closed 기본 구현**을 제공한다.
 *
 * consent-model.md 불변식: 동의 전엔 관찰조차 시작하지 않는다. 그래서 기본값은 [ConsentDecision.DENIED] 다 —
 * 실제 동의 어댑터가 등록되기 전까지는 어떤 이벤트도 적재되지 않는다(안전 기본값). 실제 어댑터가 등록되면
 * [ConditionalOnMissingBean] 로 이 기본 구현이 비활성화된다.
 */
@Configuration
class FailClosedConsentPolicyConfig {
    @Bean
    @ConditionalOnMissingBean(ConsentPolicyPort::class)
    fun failClosedConsentPolicy(): ConsentPolicyPort = ConsentPolicyPort { _, _, _ -> ConsentDecision.DENIED }
}
