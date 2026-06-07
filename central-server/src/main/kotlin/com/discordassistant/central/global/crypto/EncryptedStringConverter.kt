package com.discordassistant.central.global.crypto

import jakarta.persistence.AttributeConverter
import jakarta.persistence.Converter

/**
 * JPA 필드 암호화 컨버터(B5). `@Convert(converter = EncryptedStringConverter::class)` 로 보관 컬럼에 적용.
 * 저장 시 암호화(키 있을 때), 읽기 시 복호화. 레거시 평문은 [FieldCrypto] 가 그대로 통과시킨다(점진 암호화).
 */
@Converter
class EncryptedStringConverter : AttributeConverter<String?, String?> {
    override fun convertToDatabaseColumn(attribute: String?): String? = FieldCrypto.encrypt(attribute)

    override fun convertToEntityAttribute(dbData: String?): String? = FieldCrypto.decrypt(dbData)
}
