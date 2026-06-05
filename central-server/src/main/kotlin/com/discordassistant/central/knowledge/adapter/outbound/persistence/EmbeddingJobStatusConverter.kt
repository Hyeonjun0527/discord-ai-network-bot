package com.discordassistant.central.knowledge.adapter.outbound.persistence

import com.discordassistant.central.knowledge.domain.model.EmbeddingJobStatus
import jakarta.persistence.AttributeConverter
import jakarta.persistence.Converter

/**
 * `EmbeddingJobStatus` ↔ DB VARCHAR(소문자 와이어) 변환기.
 *
 * DB 컬럼(`embedding_index_job.status`)은 기존과 동일한 소문자 문자열이라 스키마 마이그레이션이 필요 없다.
 * 알 수 없는/깨진 문자열은 [EmbeddingJobStatus.fromWire] 가 견고하게 [EmbeddingJobStatus.QUEUED] 로 폴백한다.
 */
@Converter(autoApply = false)
class EmbeddingJobStatusConverter : AttributeConverter<EmbeddingJobStatus, String> {
    override fun convertToDatabaseColumn(attribute: EmbeddingJobStatus?): String = (attribute ?: EmbeddingJobStatus.QUEUED).wire

    override fun convertToEntityAttribute(dbData: String?): EmbeddingJobStatus = EmbeddingJobStatus.fromWire(dbData)
}
