package com.discordassistant.central.global.crypto

import jakarta.annotation.PostConstruct
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Configuration

/** 부팅 시 필드 암호화 키 주입. 키 미설정이면 평문(개발/테스트). 운영은 `NEXA_FIELD_ENC_KEY` 필수. */
@Configuration
class FieldCryptoConfig(
    @param:Value("\${nexa.field-enc-key:}") private val key: String,
) {
    @PostConstruct
    fun init() = FieldCrypto.configure(key)
}
