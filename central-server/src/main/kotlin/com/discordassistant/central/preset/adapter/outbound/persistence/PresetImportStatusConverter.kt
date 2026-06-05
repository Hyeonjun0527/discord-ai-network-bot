package com.discordassistant.central.preset.adapter.outbound.persistence

import com.discordassistant.central.preset.domain.model.PresetImportStatus
import jakarta.persistence.AttributeConverter
import jakarta.persistence.Converter

/**
 * `PresetImportStatus` ↔ DB VARCHAR(소문자 와이어) 변환기.
 *
 * DB 컬럼은 기존과 동일한 소문자 문자열(`imported`/`applied`/`needs_review`)이라
 * 스키마 마이그레이션이 필요 없다(VARCHAR 그대로). 알 수 없는/깨진 문자열은 견고하게
 * [PresetImportStatus.IMPORTED] 로 폴백한다(엔티티 기본값).
 */
@Converter(autoApply = false)
class PresetImportStatusConverter : AttributeConverter<PresetImportStatus, String> {
    override fun convertToDatabaseColumn(attribute: PresetImportStatus?): String = (attribute ?: PresetImportStatus.IMPORTED).wire

    override fun convertToEntityAttribute(dbData: String?): PresetImportStatus = PresetImportStatus.fromWire(dbData)
}
