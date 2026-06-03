package com.discordassistant.central.persistence

import com.discordassistant.central.domain.PublishedPresetStatus
import jakarta.persistence.AttributeConverter
import jakarta.persistence.Converter

/**
 * `PublishedPresetStatus` ↔ DB VARCHAR(소문자 와이어) 변환기.
 *
 * DB 컬럼은 기존과 동일한 소문자 문자열(`published`/`under_review`/`suspended`/`unlisted`/`removed`)이라
 * 스키마 마이그레이션이 필요 없다(VARCHAR 그대로). 알 수 없는/깨진 문자열은 견고하게
 * [PublishedPresetStatus.PUBLISHED] 로 폴백한다.
 */
@Converter(autoApply = false)
class PublishedPresetStatusConverter : AttributeConverter<PublishedPresetStatus, String> {
    override fun convertToDatabaseColumn(attribute: PublishedPresetStatus?): String = (attribute ?: PublishedPresetStatus.PUBLISHED).wire

    override fun convertToEntityAttribute(dbData: String?): PublishedPresetStatus = PublishedPresetStatus.fromWire(dbData)
}
