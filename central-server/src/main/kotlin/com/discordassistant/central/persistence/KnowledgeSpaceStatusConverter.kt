package com.discordassistant.central.persistence

import com.discordassistant.central.domain.KnowledgeSpaceStatus
import jakarta.persistence.AttributeConverter
import jakarta.persistence.Converter

/**
 * `KnowledgeSpaceStatus` ↔ DB VARCHAR(소문자 와이어) 변환기.
 *
 * DB 컬럼(`knowledge_space.status`)은 기존과 동일한 소문자 문자열이라 스키마 마이그레이션이 필요 없다.
 * 알 수 없는/깨진 문자열은 [KnowledgeSpaceStatus.fromWire] 가 견고하게 [KnowledgeSpaceStatus.DRAFT] 로 폴백한다.
 */
@Converter(autoApply = false)
class KnowledgeSpaceStatusConverter : AttributeConverter<KnowledgeSpaceStatus, String> {
    override fun convertToDatabaseColumn(attribute: KnowledgeSpaceStatus?): String = (attribute ?: KnowledgeSpaceStatus.DRAFT).wire

    override fun convertToEntityAttribute(dbData: String?): KnowledgeSpaceStatus = KnowledgeSpaceStatus.fromWire(dbData)
}
