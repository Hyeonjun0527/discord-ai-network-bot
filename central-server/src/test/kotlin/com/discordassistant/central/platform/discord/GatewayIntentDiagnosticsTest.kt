package com.discordassistant.central.platform.discord

import net.dv8tion.jda.api.requests.GatewayIntent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * NEXA-P03-T021 Gateway intent 진단 acceptance: 필수 intent 부재 시 조용히 오작동하지 않고 기능 상태가
 * DEGRADED 로 노출된다. MESSAGE_CONTENT/typing/reaction intent 와 기능 매핑을 검증한다.
 */
class GatewayIntentDiagnosticsTest {
    @Test
    fun `현재 정책은 콘텐츠 인텐트 켜져도 타이핑 수집은 DEGRADED`() {
        // GatewayIntentPolicy 는 GUILD_MESSAGES + GUILD_MESSAGE_REACTIONS (+MESSAGE_CONTENT)만 구독 — 타이핑 미구독.
        val granted = GatewayIntentPolicy.intents(messageContentIntentEnabled = true).toSet()
        val diagnoses = GatewayIntentDiagnostics.diagnose(granted)

        assertEquals(
            FeatureGatewayHealth.HEALTHY,
            diagnoses.getValue(IngestionFeature.MESSAGE_CONTENT_CAPTURE).health,
        )
        assertEquals(
            FeatureGatewayHealth.HEALTHY,
            diagnoses.getValue(IngestionFeature.REACTION_CAPTURE).health,
        )
        // 타이핑 intent(GUILD_MESSAGE_TYPING)가 정책에 없으므로 DEGRADED — 침묵하지 않는다.
        val typing = diagnoses.getValue(IngestionFeature.TYPING_CAPTURE)
        assertEquals(FeatureGatewayHealth.DEGRADED, typing.health)
        assertTrue(typing.missingIntents.contains(GatewayIntent.GUILD_MESSAGE_TYPING))
        assertEquals(FeatureGatewayHealth.DEGRADED, GatewayIntentDiagnostics.overallHealth(diagnoses))
    }

    @Test
    fun `콘텐츠 인텐트 꺼지면 메시지 원문 수집이 DEGRADED 로 노출된다`() {
        val granted = GatewayIntentPolicy.intents(messageContentIntentEnabled = false).toSet()
        val diagnoses = GatewayIntentDiagnostics.diagnose(granted)

        val content = diagnoses.getValue(IngestionFeature.MESSAGE_CONTENT_CAPTURE)
        assertEquals(FeatureGatewayHealth.DEGRADED, content.health)
        assertTrue(content.missingIntents.contains(GatewayIntent.MESSAGE_CONTENT))
    }

    @Test
    fun `모든 필수 intent 충족 시 HEALTHY`() {
        val granted =
            setOf(
                GatewayIntent.GUILD_MESSAGES,
                GatewayIntent.MESSAGE_CONTENT,
                GatewayIntent.GUILD_MESSAGE_REACTIONS,
                GatewayIntent.GUILD_MESSAGE_TYPING,
            )
        val diagnoses = GatewayIntentDiagnostics.diagnose(granted)

        assertTrue(diagnoses.values.all { it.health == FeatureGatewayHealth.HEALTHY })
        assertEquals(FeatureGatewayHealth.HEALTHY, GatewayIntentDiagnostics.overallHealth(diagnoses))
        assertEquals("", GatewayIntentDiagnostics.degradedGuidance(diagnoses))
    }

    @Test
    fun `DEGRADED 안내는 누락 intent 와 결과를 사람이 읽게 설명한다`() {
        val granted = setOf(GatewayIntent.GUILD_MESSAGES)
        val diagnoses = GatewayIntentDiagnostics.diagnose(granted)
        val guidance = GatewayIntentDiagnostics.degradedGuidance(diagnoses)

        assertFalse(guidance.isEmpty())
        assertTrue(guidance.contains("MESSAGE_CONTENT"))
        assertTrue(guidance.contains("GUILD_MESSAGE_TYPING"))
        assertTrue(guidance.contains("타이핑 수집"))
    }
}
