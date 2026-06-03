package com.discordassistant.central.persistence

import com.discordassistant.central.domain.RetrievalPolicyStatus
import jakarta.persistence.AttributeConverter
import jakarta.persistence.Converter

/**
 * `RetrievalPolicyStatus` ↔ DB VARCHAR(소문자 와이어) 변환기.
 *
 * DB 컬럼은 기존과 동일한 소문자 문자열(`active`/`disabled`)이라 스키마 마이그레이션이
 * 필요 없다(VARCHAR 그대로). 알 수 없는/깨진 문자열은 견고하게
 * [RetrievalPolicyStatus.ACTIVE] 로 폴백한다(엔티티 기본값).
 */
@Converter(autoApply = false)
class RetrievalPolicyStatusConverter : AttributeConverter<RetrievalPolicyStatus, String> {
    override fun convertToDatabaseColumn(attribute: RetrievalPolicyStatus?): String = (attribute ?: RetrievalPolicyStatus.ACTIVE).wire

    override fun convertToEntityAttribute(dbData: String?): RetrievalPolicyStatus = RetrievalPolicyStatus.fromWire(dbData)
}
