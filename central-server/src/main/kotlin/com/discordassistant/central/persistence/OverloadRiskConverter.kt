package com.discordassistant.central.persistence

import com.discordassistant.central.domain.OverloadRisk
import jakarta.persistence.AttributeConverter
import jakarta.persistence.Converter

/**
 * `OverloadRisk` ↔ DB VARCHAR(소문자 와이어: normal/low/high/critical) 변환기.
 * 기존 값 보존(마이그레이션 없음). 빈/알 수 없는 값은 normalize 규칙으로 NORMAL 폴백.
 */
@Converter(autoApply = false)
class OverloadRiskConverter : AttributeConverter<OverloadRisk, String> {
    override fun convertToDatabaseColumn(attribute: OverloadRisk?): String = (attribute ?: OverloadRisk.NORMAL).wire

    override fun convertToEntityAttribute(dbData: String?): OverloadRisk = OverloadRisk.normalize(dbData)
}
