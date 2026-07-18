package com.discordassistant.central.platform.discord.nexa

import com.discordassistant.central.global.crypto.FieldCryptoConfig
import com.discordassistant.central.routing.application.CloudLlm
import jakarta.annotation.PostConstruct
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component

/** 자율 전송을 켠 배포가 필수 암호화·추론 전제조건 없이 조용히 침묵하지 않도록 부팅을 중단한다. */
@Component
@ConditionalOnProperty(name = ["central.nexa.autonomous-send.enabled"], havingValue = "true")
class NexaAutonomousSendReadinessGuard(
    private val fieldCryptoConfig: FieldCryptoConfig,
    private val cloudLlm: CloudLlm,
) {
    @PostConstruct
    fun validate() {
        check(fieldCryptoConfig.isConfigured()) {
            "NEXA autonomous-send requires NEXA_FIELD_ENC_KEY"
        }
        check(cloudLlm.isEnabled()) {
            "NEXA autonomous-send requires an enabled CloudLlm"
        }
    }
}
