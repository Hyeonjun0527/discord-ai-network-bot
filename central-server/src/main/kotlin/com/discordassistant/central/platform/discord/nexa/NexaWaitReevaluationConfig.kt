package com.discordassistant.central.platform.discord.nexa

import com.discordassistant.central.actionruntime.application.port.out.WaitReevaluationHandler
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/** WAIT outbox를 FINAL participation bridge의 최신 장면 재판단으로 연결한다. */
@Configuration
@ConditionalOnProperty(name = ["central.nexa.autonomous-send.enabled"], havingValue = "true")
class NexaWaitReevaluationConfig {
    @Bean
    fun nexaWaitReevaluationHandler(bridge: NexaParticipationEmitBridge): WaitReevaluationHandler =
        WaitReevaluationHandler(bridge::onWaitReevaluation)
}
