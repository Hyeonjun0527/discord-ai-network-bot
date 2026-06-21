package com.discordassistant.central.platform.discord

import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.actuate.health.Health
import org.springframework.boot.actuate.health.HealthIndicator
import org.springframework.stereotype.Component
import java.time.Instant

@Component
class DiscordGatewayStatus(
    @param:Value("\${central.discord.enabled:false}") private val discordEnabled: Boolean,
) {
    @Volatile var ready: Boolean = false

    @Volatile var messageContentIntentEnabled: Boolean = false

    @Volatile var mentionAskEnabled: Boolean = false

    @Volatile var lastShutdownCode: Int? = null

    @Volatile var lastProblem: String? = null

    @Volatile var updatedAt: Instant = Instant.EPOCH

    fun markStarting(messageContentIntent: Boolean) {
        ready = false
        messageContentIntentEnabled = messageContentIntent
        mentionAskEnabled = false
        lastProblem = null
        updatedAt = Instant.now()
    }

    fun markReady(messageContentIntent: Boolean) {
        ready = true
        messageContentIntentEnabled = messageContentIntent
        mentionAskEnabled = messageContentIntent
        lastShutdownCode = null
        lastProblem = null
        updatedAt = Instant.now()
    }

    fun markSafeFallback(reason: String) {
        ready = false
        messageContentIntentEnabled = false
        mentionAskEnabled = false
        lastProblem = reason
        updatedAt = Instant.now()
    }

    fun markShutdown(
        code: Int,
        problem: String?,
    ) {
        ready = false
        lastShutdownCode = code
        lastProblem = problem
        updatedAt = Instant.now()
    }

    fun disabledOrReady(): Boolean = !discordEnabled || ready
}

@Component("discordGateway")
class DiscordGatewayHealthIndicator(
    private val status: DiscordGatewayStatus,
) : HealthIndicator {
    override fun health(): Health {
        // 구독 intent 진단(T021): 현재 메시지 콘텐츠 인텐트 상태로 NEXA 수집 기능의 DEGRADED 여부를 드러낸다.
        // 인텐트 부재가 침묵으로 묻히지 않고 health 에 노출된다(조용한 오작동 금지).
        val granted = GatewayIntentPolicy.intents(status.messageContentIntentEnabled).toSet()
        val diagnoses = GatewayIntentDiagnostics.diagnose(granted)
        val ingestionHealth = GatewayIntentDiagnostics.overallHealth(diagnoses)
        val degradedGuidance = GatewayIntentDiagnostics.degradedGuidance(diagnoses)

        val builder = if (status.disabledOrReady()) Health.up() else Health.down()
        return builder
            .withDetail("ready", status.ready)
            .withDetail("messageContentIntentEnabled", status.messageContentIntentEnabled)
            .withDetail("mentionAskEnabled", status.mentionAskEnabled)
            .withDetail("lastShutdownCode", status.lastShutdownCode ?: "")
            .withDetail("lastProblem", status.lastProblem ?: "")
            .withDetail("ingestionGatewayHealth", ingestionHealth.name)
            .withDetail("ingestionDegradedFeatures", degradedGuidance)
            .withDetail("updatedAt", status.updatedAt.toString())
            .build()
    }
}
