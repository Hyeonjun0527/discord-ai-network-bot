package com.discordassistant.central.platform.discord.nexa

import com.discordassistant.central.participation.application.judge.NiaJudgeOutputParser
import com.discordassistant.central.participation.application.judge.NiaJudgePromptAssembler
import com.discordassistant.central.participation.application.judge.NiaParticipationJudge
import com.discordassistant.central.participation.application.judge.SingleParticipationJudgePort
import com.discordassistant.central.participation.application.port.out.NiaJudgeLlmPort
import com.discordassistant.central.participation.application.port.out.ShadowPredictionStorePort
import com.discordassistant.central.participation.application.shadow.NiaJudgeShadowService
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

/** NIA single judge runtime wiring. A concrete [NiaJudgeLlmPort] makes the raw-scene judge available to the bridge. */
@Configuration
class NexaJudgeRuntimeConfig {
    @Bean
    @ConditionalOnBean(NiaJudgeLlmPort::class)
    @ConditionalOnMissingBean(SingleParticipationJudgePort::class)
    fun niaSingleParticipationJudge(llmPort: NiaJudgeLlmPort): SingleParticipationJudgePort =
        NiaParticipationJudge(
            promptAssembler = NiaJudgePromptAssembler(),
            llmPort = llmPort,
            outputParser = NiaJudgeOutputParser(),
        )

    @Bean
    @ConditionalOnBean(value = [SingleParticipationJudgePort::class, ShadowPredictionStorePort::class])
    @ConditionalOnMissingBean(NiaJudgeShadowService::class)
    fun niaJudgeShadowService(
        judge: SingleParticipationJudgePort,
        predictionStore: ShadowPredictionStorePort,
    ): NiaJudgeShadowService = NiaJudgeShadowService(judge, predictionStore, Clock.systemUTC())
}
