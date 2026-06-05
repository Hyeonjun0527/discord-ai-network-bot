package com.discordassistant.central.multiresponse.adapter.outbound.persistence

import com.discordassistant.central.multiresponse.domain.model.SynthesisStatus
import jakarta.persistence.AttributeConverter
import jakarta.persistence.Converter

/**
 * `SynthesisStatus` ↔ DB VARCHAR(소문자 와이어) 변환기 (#123 `PresetStatusConverter` 와 동일 패턴).
 *
 * DB 컬럼은 기존과 동일한 소문자 문자열(`pending`/`completed`)이라 스키마 마이그레이션이 필요 없다
 * (VARCHAR 그대로). 알 수 없는/깨진 문자열은 견고하게 [SynthesisStatus.PENDING] 으로 폴백한다.
 */
@Converter(autoApply = false)
class SynthesisStatusConverter : AttributeConverter<SynthesisStatus, String> {
    override fun convertToDatabaseColumn(attribute: SynthesisStatus?): String = (attribute ?: SynthesisStatus.PENDING).wire

    override fun convertToEntityAttribute(dbData: String?): SynthesisStatus = SynthesisStatus.fromWire(dbData)
}
