package com.discordassistant.central.participation.adapter.outbound.policy.baseline

import com.discordassistant.central.participation.adapter.outbound.policy.legacy.LegacyAutoRespondPolicyConfig
import com.discordassistant.central.participation.application.port.out.ParticipationPolicyPort
import com.discordassistant.central.platform.discord.nexa.NexaParticipationEmitBridge
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean

class BaselineParticipationPolicyConfigTest {
    @Test
    fun `participation baseline bean uses dedicated qualifier and does not collide with legacy auto respond`() {
        val participationBean = BaselineParticipationPolicyConfig::class.java.getDeclaredMethod("baselineParticipationPolicy")
        val legacyBean = LegacyAutoRespondPolicyConfig::class.java.getDeclaredMethod("legacyAutoRespondPolicy")

        assertThat(participationBean.getAnnotation(Bean::class.java).name)
            .containsExactly(BaselineParticipationPolicyConfig.PARTICIPATION_EVAL_POLICY_BEAN)
        assertThat(participationBean.getAnnotation(ConditionalOnMissingBean::class.java).name)
            .containsExactly(BaselineParticipationPolicyConfig.PARTICIPATION_EVAL_POLICY_BEAN)
        assertThat(legacyBean.getAnnotation(Bean::class.java).name)
            .containsExactly("legacyAutoRespondPolicy")
        assertThat(BaselineParticipationPolicyConfig.PARTICIPATION_EVAL_POLICY_BEAN)
            .isNotEqualTo("legacyAutoRespondPolicy")
    }

    @Test
    fun `nexa participation bridge explicitly asks for participation eval policy`() {
        val constructor =
            NexaParticipationEmitBridge::class.java.declaredConstructors
                .first { constructor ->
                    constructor.parameters.any { it.type == ParticipationPolicyPort::class.java }
                }
        val policyParameter = constructor.parameters.single { it.type == ParticipationPolicyPort::class.java }

        assertThat(policyParameter.getAnnotation(Qualifier::class.java).value)
            .isEqualTo(BaselineParticipationPolicyConfig.PARTICIPATION_EVAL_POLICY_BEAN)
    }

    @Test
    fun `default policy remains a clearly named cooldown heuristic baseline`() {
        val policy = BaselineParticipationPolicyConfig().baselineParticipationPolicy()

        assertThat(policy).isInstanceOf(CooldownHeuristicPolicy::class.java)
        assertThat(CooldownHeuristicPolicy.MODEL_VERSION).startsWith("baseline-cooldown-heuristic")
    }
}
