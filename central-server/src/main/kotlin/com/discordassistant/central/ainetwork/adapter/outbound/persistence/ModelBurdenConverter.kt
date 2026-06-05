package com.discordassistant.central.ainetwork.adapter.outbound.persistence

import com.discordassistant.central.shared.ModelBurden
import jakarta.persistence.AttributeConverter
import jakarta.persistence.Converter

/**
 * `ModelBurden` ↔ DB VARCHAR(대문자 enum 이름: LIGHT/STANDARD/HEAVY/RESTRICTED) 변환기.
 * 기존 값 보존(마이그레이션 없음). "DEEP" 별칭·알 수 없는 값은 LIGHT 폴백(fromName 규칙).
 */
@Converter(autoApply = false)
class ModelBurdenConverter : AttributeConverter<ModelBurden, String> {
    override fun convertToDatabaseColumn(attribute: ModelBurden?): String = (attribute ?: ModelBurden.LIGHT).name

    override fun convertToEntityAttribute(dbData: String?): ModelBurden = ModelBurden.fromName(dbData) ?: ModelBurden.LIGHT
}
