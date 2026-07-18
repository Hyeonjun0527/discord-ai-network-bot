package com.discordassistant.central.socialpolicy.adapter.inbound

import com.discordassistant.central.actionruntime.application.port.out.ActionReevaluationPort
import com.discordassistant.central.actionruntime.application.port.out.ReevaluationTarget
import com.discordassistant.central.actionruntime.application.scheduler.SceneEvidenceProvider
import com.discordassistant.central.actionruntime.domain.service.SceneEvidence
import com.discordassistant.central.socialpolicy.application.port.out.SceneBeliefStatePort
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/** 영속 장면 projection을 예약 행동의 실행 직전 재평가 경계에 연결한다. */
@Configuration
class SceneBeliefActionRuntimeConfig {
    @Bean
    @ConditionalOnMissingBean(ActionReevaluationPort::class)
    fun sceneBeliefActionReevaluationPort(sceneBeliefState: SceneBeliefStatePort): ActionReevaluationPort =
        object : ActionReevaluationPort {
            override fun currentContextVersion(target: ReevaluationTarget): Long? = sceneBeliefState.find(target.threadId)?.contextVersion

            override fun stillValid(
                decisionId: String,
                target: ReevaluationTarget,
                scheduledContextVersion: Long,
                currentContextVersion: Long,
            ): Boolean = false
        }

    @Bean
    @ConditionalOnMissingBean(SceneEvidenceProvider::class)
    fun sceneBeliefEvidenceProvider(sceneBeliefState: SceneBeliefStatePort): SceneEvidenceProvider =
        SceneEvidenceProvider { action ->
            val state = sceneBeliefState.find(action.target.threadId)
            SceneEvidence(
                humanRepliesSinceSchedule = 0,
                currentFocusThreadId = state?.focusThreadKey,
                targetExpired = state == null,
            )
        }
}
