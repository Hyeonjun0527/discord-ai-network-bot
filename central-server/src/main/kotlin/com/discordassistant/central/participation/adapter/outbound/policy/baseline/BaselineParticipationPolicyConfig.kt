package com.discordassistant.central.participation.adapter.outbound.policy.baseline

import com.discordassistant.central.participation.application.port.out.ParticipationPolicyPort
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * NEXA participation 평가 정책 기본 bean 배선(participation adapter — 자기 도메인 baseline 참조 허용).
 *
 * participation 자발 발화 wiring(participation-activation-plan 단계 1)이 SPEAK/IGNORE 분포를 얻으려면
 * [ParticipationPolicyPort] bean 이 필요하다. 관측 가능한 최소 신호(멘션·최근 NEXA 발화량)만 보는
 * [CooldownHeuristicPolicy] 를 안전한 하한 baseline 으로 등록한다 — 단계 1 은 SHADOW_PREDICT(전송 0)라 정책 품질은
 * 관측 대상일 뿐 사용자 영향이 없으므로, 단순·결정론 baseline 으로 시작한다.
 *
 * 더 풍부한 정책(ONNX/learned/gRPC) bean 이 등록되면 [ConditionalOnMissingBean] 으로 이 기본이 비활성화된다.
 *
 * 경계: 이 config 는 participation **adapter** 라 자기 도메인의 baseline 구현을 참조해도 된다 — 기존 도메인(platform
 * 등)이 NEXA adapter 내부를 직접 참조하지 않게(NexaArchitectureTest 불변식 2), 정책 bean 조립은 여기서 한다.
 */
@Configuration
class BaselineParticipationPolicyConfig {
    /**
     * participation 평가 기본 정책(cooldown baseline) — 멘션이면 발화, 아니면 최근 발화량으로 cooldown.
     * 안정 bean 이름 [PARTICIPATION_EVAL_POLICY_BEAN] 으로 노출해, 같은 타입의 다른 정책 bean(legacyAutoRespond 등)과
     * 구분해 [NexaParticipationEmitBridge] 가 `@Qualifier` 로 명시 선택한다(NoUniqueBean 회피).
     */
    @Bean(name = [PARTICIPATION_EVAL_POLICY_BEAN])
    @ConditionalOnMissingBean(name = [PARTICIPATION_EVAL_POLICY_BEAN])
    fun baselineParticipationPolicy(): ParticipationPolicyPort = CooldownHeuristicPolicy()

    companion object {
        /** participation 자발 발화 평가에 쓰는 정책 bean 의 안정 qualifier 이름. */
        const val PARTICIPATION_EVAL_POLICY_BEAN: String = "participationEvalPolicy"
    }
}
