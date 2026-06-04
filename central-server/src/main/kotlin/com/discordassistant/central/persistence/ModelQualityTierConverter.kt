package com.discordassistant.central.persistence

import com.discordassistant.central.domain.ModelQualityTier
import jakarta.persistence.AttributeConverter
import jakarta.persistence.Converter

/**
 * `ModelQualityTier` ↔ DB VARCHAR(소문자 와이어: unknown/standard/high/specialized) 변환기.
 * 기존 값 보존(마이그레이션 없음). 알 수 없는 값은 UNKNOWN 폴백.
 */
@Converter(autoApply = false)
class ModelQualityTierConverter : AttributeConverter<ModelQualityTier, String> {
    override fun convertToDatabaseColumn(attribute: ModelQualityTier?): String = (attribute ?: ModelQualityTier.UNKNOWN).wire

    override fun convertToEntityAttribute(dbData: String?): ModelQualityTier = ModelQualityTier.fromWire(dbData)
}
