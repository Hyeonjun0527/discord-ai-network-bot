package com.discordassistant.central.conversation.adapter.outbound.persistence

import com.discordassistant.central.conversation.application.port.out.ConversationProjectionPort
import com.discordassistant.central.conversation.domain.model.event.ChannelId
import com.discordassistant.central.conversation.domain.model.event.EventId
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * 기본 [ConversationProjectionPort] 어댑터(NEXA-P03-T012). 실제 projection worker 전달 채널은 후속 task 에서
 * 붙는다 — 그 전까지 outbox publisher 가 wiring 되도록 **안전한 기본 구현**을 제공한다.
 *
 * 전달 키(eventId/channelId)만 로깅하고 부수효과 없이 반환한다(멱등 — 같은 키 재전달이 안전하다). 원문은 받지
 * 않으므로 로그에 PII 가 남지 않는다(logging-boundary.md). 실제 어댑터가 등록되면 [ConditionalOnMissingBean] 로
 * 이 기본 구현이 비활성화된다.
 */
@Configuration
class LoggingConversationProjectionConfig {
    @Bean
    @ConditionalOnMissingBean(ConversationProjectionPort::class)
    fun loggingConversationProjection(): ConversationProjectionPort =
        ConversationProjectionPort { eventId: EventId, channelId: ChannelId ->
            log.debug("conversation outbox -> projection(no-op): eventId={}, channelId={}", eventId.value, channelId.value)
        }

    private companion object {
        private val log = LoggerFactory.getLogger(LoggingConversationProjectionConfig::class.java)
    }
}
