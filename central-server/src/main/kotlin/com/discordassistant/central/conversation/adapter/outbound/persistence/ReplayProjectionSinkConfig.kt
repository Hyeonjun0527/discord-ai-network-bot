package com.discordassistant.central.conversation.adapter.outbound.persistence

import com.discordassistant.central.conversation.application.replay.ReplayEventKey
import com.discordassistant.central.conversation.application.replay.ReplayProjectionSink
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * 기본 [ReplayProjectionSink] 어댑터(NEXA-P03-T019). 실제 projection 읽기 모델 갱신 sink 는 후속 task 에서
 * 붙는다 — 그 전까지 replay 유스케이스가 wiring 되도록 **side-effect-free 기본 구현**을 제공한다.
 *
 * 재생 키(eventId/channelId)만 로깅하고 외부 전송 없이 반환한다(acceptance T019: replay 중 Discord 전송 금지).
 * 원문을 받지 않으므로 로그에 PII 가 남지 않는다(logging-boundary.md). 실제 sink 가 등록되면
 * [ConditionalOnMissingBean] 으로 이 기본 구현이 비활성화된다.
 */
@Configuration
class ReplayProjectionSinkConfig {
    @Bean
    @ConditionalOnMissingBean(ReplayProjectionSink::class)
    fun loggingReplayProjectionSink(): ReplayProjectionSink =
        ReplayProjectionSink { eventKey: ReplayEventKey, projectionVersion: Int ->
            log.debug(
                "replay -> projection(no-op): eventId={}, channelId={}, version={}",
                eventKey.eventId.value,
                eventKey.channelId.value,
                projectionVersion,
            )
        }

    private companion object {
        private val log = LoggerFactory.getLogger(ReplayProjectionSinkConfig::class.java)
    }
}
