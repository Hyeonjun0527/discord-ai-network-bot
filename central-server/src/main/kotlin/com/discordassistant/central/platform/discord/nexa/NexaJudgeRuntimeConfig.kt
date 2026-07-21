package com.discordassistant.central.platform.discord.nexa

import com.discordassistant.central.participation.application.judge.NiaJudgeOutputParser
import com.discordassistant.central.participation.application.judge.NiaJudgePromptAssembler
import com.discordassistant.central.participation.application.judge.NiaParticipationJudge
import com.discordassistant.central.participation.application.judge.SingleParticipationJudgePort
import com.discordassistant.central.participation.application.port.out.NiaJudgeLlmPort
import com.discordassistant.central.participation.application.port.out.ShadowPredictionStorePort
import com.discordassistant.central.participation.application.shadow.NiaJudgeShadowService
import com.discordassistant.central.shared.NiaPromptSource
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

/** NIA single judge runtime wiring. A concrete [NiaJudgeLlmPort] makes the raw-scene judge available to the bridge. */
@Configuration
class NexaJudgeRuntimeConfig {
    @Bean
    @ConditionalOnMissingBean(NiaJudgePromptAssembler::class)
    fun niaJudgePromptAssembler(promptSource: NiaPromptSource): NiaJudgePromptAssembler =
        NiaJudgePromptAssembler(promptSource = promptSource)

    @Bean
    @ConditionalOnBean(NiaJudgeLlmPort::class)
    @ConditionalOnMissingBean(SingleParticipationJudgePort::class)
    fun niaSingleParticipationJudge(
        llmPort: NiaJudgeLlmPort,
        promptAssembler: NiaJudgePromptAssembler,
        promptSource: NiaPromptSource,
    ): SingleParticipationJudgePort =
        NiaParticipationJudge(
            promptAssembler = promptAssembler,
            llmPort = llmPort,
            outputParser = NiaJudgeOutputParser(),
            promptSource = promptSource,
        )

    @Bean
    @ConditionalOnBean(value = [SingleParticipationJudgePort::class, ShadowPredictionStorePort::class])
    @ConditionalOnMissingBean(NiaJudgeShadowService::class)
    fun niaJudgeShadowService(
        judge: SingleParticipationJudgePort,
        predictionStore: ShadowPredictionStorePort,
    ): NiaJudgeShadowService = NiaJudgeShadowService(judge, predictionStore, Clock.systemUTC())
}
