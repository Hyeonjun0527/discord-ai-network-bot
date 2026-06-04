package com.discordassistant.central.persistence

import com.discordassistant.central.domain.ProviderAvailability
import jakarta.persistence.AttributeConverter
import jakarta.persistence.Converter

/**
 * `ProviderAvailability` ↔ DB VARCHAR(대문자 와이어: ONLINE/OFFLINE/OVERLOADED/PENDING/UNKNOWN) 변환기.
 * 기존 문자열 컬럼 값을 그대로 보존하므로 스키마 마이그레이션이 없다. 알 수 없는 값은 UNKNOWN 폴백.
 */
@Converter(autoApply = false)
class ProviderAvailabilityConverter : AttributeConverter<ProviderAvailability, String> {
    override fun convertToDatabaseColumn(attribute: ProviderAvailability?): String = (attribute ?: ProviderAvailability.UNKNOWN).wire

    override fun convertToEntityAttribute(dbData: String?): ProviderAvailability = ProviderAvailability.fromWire(dbData)
}
