package com.discordassistant.central.preset.adapter.outbound.persistence

import com.discordassistant.central.preset.domain.model.PresetReportStatus
import jakarta.persistence.AttributeConverter
import jakarta.persistence.Converter

/**
 * `PresetReportStatus` ↔ DB VARCHAR(소문자 와이어) 변환기.
 *
 * DB 컬럼은 기존과 동일한 소문자 문자열(`open`/`dismiss`/`suspend`/`remove`/`reviewed`)이라
 * 스키마 마이그레이션이 필요 없다(VARCHAR 그대로). 알 수 없는/깨진 문자열은 견고하게
 * [PresetReportStatus.OPEN] 으로 폴백한다(엔티티 기본값).
 */
@Converter(autoApply = false)
class PresetReportStatusConverter : AttributeConverter<PresetReportStatus, String> {
    override fun convertToDatabaseColumn(attribute: PresetReportStatus?): String = (attribute ?: PresetReportStatus.OPEN).wire

    override fun convertToEntityAttribute(dbData: String?): PresetReportStatus = PresetReportStatus.fromWire(dbData)
}
