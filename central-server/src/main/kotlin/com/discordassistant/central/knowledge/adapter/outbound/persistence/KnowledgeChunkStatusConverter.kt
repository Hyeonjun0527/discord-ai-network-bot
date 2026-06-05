package com.discordassistant.central.knowledge.adapter.outbound.persistence

import com.discordassistant.central.knowledge.domain.model.KnowledgeChunkStatus
import jakarta.persistence.AttributeConverter
import jakarta.persistence.Converter

/**
 * `KnowledgeChunkStatus` ↔ DB VARCHAR(소문자 와이어) 변환기.
 *
 * DB 컬럼(`knowledge_chunk.status`)은 기존과 동일한 소문자 문자열이라 스키마 마이그레이션이 필요 없다.
 * 알 수 없는/깨진 문자열은 [KnowledgeChunkStatus.fromWire] 가 견고하게 [KnowledgeChunkStatus.READY] 로 폴백한다.
 */
@Converter(autoApply = false)
class KnowledgeChunkStatusConverter : AttributeConverter<KnowledgeChunkStatus, String> {
    override fun convertToDatabaseColumn(attribute: KnowledgeChunkStatus?): String = (attribute ?: KnowledgeChunkStatus.READY).wire

    override fun convertToEntityAttribute(dbData: String?): KnowledgeChunkStatus = KnowledgeChunkStatus.fromWire(dbData)
}
