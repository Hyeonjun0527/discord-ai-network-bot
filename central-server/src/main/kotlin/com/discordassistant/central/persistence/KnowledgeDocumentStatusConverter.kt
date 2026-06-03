package com.discordassistant.central.persistence

import com.discordassistant.central.domain.KnowledgeDocumentStatus
import jakarta.persistence.AttributeConverter
import jakarta.persistence.Converter

/**
 * `KnowledgeDocumentStatus` ↔ DB VARCHAR(소문자 와이어) 변환기.
 *
 * DB 컬럼(`knowledge_document.status`)은 기존과 동일한 소문자 문자열이라 스키마 마이그레이션이 필요 없다.
 * 알 수 없는/깨진 문자열은 [KnowledgeDocumentStatus.fromWire] 가 견고하게 [KnowledgeDocumentStatus.PARSED] 로 폴백한다.
 */
@Converter(autoApply = false)
class KnowledgeDocumentStatusConverter : AttributeConverter<KnowledgeDocumentStatus, String> {
    override fun convertToDatabaseColumn(attribute: KnowledgeDocumentStatus?): String = (attribute ?: KnowledgeDocumentStatus.PARSED).wire

    override fun convertToEntityAttribute(dbData: String?): KnowledgeDocumentStatus = KnowledgeDocumentStatus.fromWire(dbData)
}
