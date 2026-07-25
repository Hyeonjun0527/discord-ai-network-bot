package com.discordassistant.central.platform.discord

import net.dv8tion.jda.api.requests.GatewayIntent

/**
 * Gateway intent 설정 진단(NEXA-P03-T021). 부팅 시 구독한 [GatewayIntent] 집합을 NEXA 수집 기능이 요구하는
 * intent 와 대조해, 누락이 있으면 **조용히 오작동하지 않고** 그 기능을 DEGRADED 로 드러낸다.
 *
 * `GatewayIntentPolicy.intents()` 는 메시지 콘텐츠와 typing intent를 각각 설정으로 구독한다. typing 수집(T005,
 * `GUILD_MESSAGE_TYPING`) 설정이 꺼졌거나 실제 구독 intent에서 빠지면 `onUserTyping` 이 호출되지 않는다. 이 진단은
 * 각 수집 기능의 필수 intent 를 명시하고, 부재 시 [FeatureGatewayHealth.DEGRADED] 로 노출해 운영자가 원인을 즉시 안다.
 *
 * 순수성: JDA enum([GatewayIntent])만 보고 Spring/JPA 를 참조하지 않는다(어댑터-로컬 순수 진단 — 단위 테스트 가능).
 * 운영 게이트웨이에 연결하지 않는다 — **구독 의도(intent 집합)** 만 검사한다.
 *
 * **acceptance(T021)**: MESSAGE_CONTENT/typing/reaction 관련 intent 와 그 기능 매핑을 진단한다. 필수 intent 부재 시
 * 해당 기능이 DEGRADED 로 나오고(침묵 금지), 모두 충족하면 HEALTHY 다.
 */
object GatewayIntentDiagnostics {
    /**
     * 구독한 [grantedIntents] 로 각 수집 기능의 게이트웨이 건강 상태를 진단한다. 누락 intent 가 있는 기능은
     * DEGRADED, 충족하면 HEALTHY 다. 결과는 기능 enum → 진단으로 결정론적 매핑(드리프트 방지).
     */
    fun diagnose(grantedIntents: Set<GatewayIntent>): Map<IngestionFeature, FeatureDiagnosis> =
        IngestionFeature.entries.associateWith { feature ->
            val missing = feature.requiredIntents - grantedIntents
            FeatureDiagnosis(
                feature = feature,
                requiredIntents = feature.requiredIntents,
                missingIntents = missing,
                health = if (missing.isEmpty()) FeatureGatewayHealth.HEALTHY else FeatureGatewayHealth.DEGRADED,
            )
        }

    /** 진단 중 하나라도 DEGRADED 면 전체 수집이 DEGRADED(운영 health 요약용). */
    fun overallHealth(diagnoses: Map<IngestionFeature, FeatureDiagnosis>): FeatureGatewayHealth =
        if (diagnoses.values.any { it.health == FeatureGatewayHealth.DEGRADED }) {
            FeatureGatewayHealth.DEGRADED
        } else {
            FeatureGatewayHealth.HEALTHY
        }

    /** DEGRADED 기능들의 사람이 읽는 안내(운영자 노출). 모두 HEALTHY 면 빈 문자열. */
    fun degradedGuidance(diagnoses: Map<IngestionFeature, FeatureDiagnosis>): String =
        diagnoses.values
            .filter { it.health == FeatureGatewayHealth.DEGRADED }
            .joinToString("; ") { d ->
                val names = d.missingIntents.joinToString(",") { it.name }
                "${d.feature.label}: intent 누락($names) — ${d.feature.consequence}"
            }
}

/** NEXA 수집 기능과 그 기능이 동작하려면 반드시 구독해야 하는 게이트웨이 intent(SSOT). */
enum class IngestionFeature(
    val label: String,
    val requiredIntents: Set<GatewayIntent>,
    /** 이 intent 가 없을 때 침묵 대신 알려야 할 결과(운영자 안내). */
    val consequence: String,
) {
    /** 메시지 원문 수집 — MESSAGE_CONTENT 없으면 원문이 IntentMissing 으로만 들어온다. */
    MESSAGE_CONTENT_CAPTURE(
        label = "메시지 원문 수집",
        requiredIntents = setOf(GatewayIntent.GUILD_MESSAGES, GatewayIntent.MESSAGE_CONTENT),
        consequence = "원문이 관찰되지 않고 IntentMissing 으로만 기록됩니다",
    ),

    /** 리액션 수집 — GUILD_MESSAGE_REACTIONS 필요. */
    REACTION_CAPTURE(
        label = "리액션 수집",
        requiredIntents = setOf(GatewayIntent.GUILD_MESSAGE_REACTIONS),
        consequence = "리액션 add/remove 이벤트가 수집되지 않습니다",
    ),

    /** 타이핑 수집 — GUILD_MESSAGE_TYPING 필요. */
    TYPING_CAPTURE(
        label = "타이핑 수집",
        requiredIntents = setOf(GatewayIntent.GUILD_MESSAGE_TYPING),
        consequence = "onUserTyping 이 호출되지 않아 타이핑 신호가 누락됩니다",
    ),
}

/** 한 기능의 게이트웨이 intent 진단 결과(원문 미포함 — intent 메타만). */
data class FeatureDiagnosis(
    val feature: IngestionFeature,
    val requiredIntents: Set<GatewayIntent>,
    val missingIntents: Set<GatewayIntent>,
    val health: FeatureGatewayHealth,
)

/** 기능의 게이트웨이 건강 상태 — 필수 intent 가 모두 있으면 HEALTHY, 하나라도 없으면 DEGRADED(침묵 금지). */
enum class FeatureGatewayHealth {
    HEALTHY,
    DEGRADED,
}
