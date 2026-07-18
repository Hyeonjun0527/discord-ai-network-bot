package com.discordassistant.central.global.crypto

import jakarta.annotation.PostConstruct
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Configuration

/** 부팅 시 필드 암호화 키 주입. 레거시는 미설정 시 평문이나 Discord routing 예약은 fail-closed한다. */
@Configuration
class FieldCryptoConfig(
    @param:Value("\${nexa.field-enc-key:}") private val key: String,
) {
    @PostConstruct
    fun init() = FieldCrypto.configure(key)

    fun isConfigured(): Boolean = FieldCrypto.isConfigured()
}
