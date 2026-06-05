package com.discordassistant.central.knowledge.adapter.outbound.persistence

import com.discordassistant.central.knowledge.domain.model.KnowledgeSourceStatus
import jakarta.persistence.AttributeConverter
import jakarta.persistence.Converter

/**
 * `KnowledgeSourceStatus` ↔ DB VARCHAR 변환기.
 *
 * DB 컬럼(`knowledge_source.status`)은 고정 토큰뿐 아니라 `rejected:<사유>` / `deleted:<사유>` 처럼
 * 사유가 콜론으로 붙는 동적 문자열을 담는다. [KnowledgeSourceStatus.wire] 가 그 포맷을 정확히 재현하므로
 * 스키마 마이그레이션 없이 기존 바이트를 그대로 저장/조회한다. 알 수 없는/깨진 문자열은
 * [KnowledgeSourceStatus.fromWire] 가 견고하게 [KnowledgeSourceStatus.Kind.PENDING] 로 폴백한다(사유는 보존).
 */
@Converter(autoApply = false)
class KnowledgeSourceStatusConverter : AttributeConverter<KnowledgeSourceStatus, String> {
    override fun convertToDatabaseColumn(attribute: KnowledgeSourceStatus?): String = (attribute ?: KnowledgeSourceStatus.PENDING).wire

    override fun convertToEntityAttribute(dbData: String?): KnowledgeSourceStatus = KnowledgeSourceStatus.fromWire(dbData)
}
