package com.discordassistant.central.persistence

import com.discordassistant.central.domain.PresetStatus
import jakarta.persistence.AttributeConverter
import jakarta.persistence.Converter

/**
 * `PresetStatus` ↔ DB VARCHAR(소문자 와이어) 변환기.
 *
 * DB 컬럼은 기존과 동일한 소문자 문자열(`draft`/`published`/`removed`)이라 스키마 마이그레이션이
 * 필요 없다(VARCHAR 그대로). 알 수 없는/깨진 문자열은 견고하게 [PresetStatus.DRAFT] 로 폴백한다.
 */
@Converter(autoApply = false)
class PresetStatusConverter : AttributeConverter<PresetStatus, String> {
    override fun convertToDatabaseColumn(attribute: PresetStatus?): String = (attribute ?: PresetStatus.DRAFT).wire

    override fun convertToEntityAttribute(dbData: String?): PresetStatus = PresetStatus.fromWire(dbData)
}
