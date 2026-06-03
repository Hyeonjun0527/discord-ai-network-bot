package com.discordassistant.central.persistence

import com.discordassistant.central.domain.FeedbackStatus
import jakarta.persistence.AttributeConverter
import jakarta.persistence.Converter

/**
 * `FeedbackStatus` ↔ DB VARCHAR(소문자 와이어) 변환기.
 *
 * DB 컬럼은 기존과 동일한 소문자 문자열(`open`/`needs_review`/`resolved`/`dismissed`)이라
 * 스키마 마이그레이션이 필요 없다(VARCHAR 그대로). 알 수 없는/깨진 문자열은 견고하게
 * [FeedbackStatus.OPEN] 으로 폴백한다(엔티티 기본값).
 */
@Converter(autoApply = false)
class FeedbackStatusConverter : AttributeConverter<FeedbackStatus, String> {
    override fun convertToDatabaseColumn(attribute: FeedbackStatus?): String = (attribute ?: FeedbackStatus.OPEN).wire

    override fun convertToEntityAttribute(dbData: String?): FeedbackStatus = FeedbackStatus.fromWire(dbData)
}
