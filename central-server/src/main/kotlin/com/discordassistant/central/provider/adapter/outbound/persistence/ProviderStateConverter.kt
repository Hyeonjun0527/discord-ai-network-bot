package com.discordassistant.central.provider.adapter.outbound.persistence

import com.discordassistant.central.provider.domain.model.ProviderState
import jakarta.persistence.AttributeConverter
import jakarta.persistence.Converter
import org.slf4j.LoggerFactory

/**
 * `ProviderState` ↔ DB VARCHAR(enum.name) 변환기.
 *
 * DB 컬럼은 그대로 문자열(enum 이름)이라 스키마 마이그레이션이 필요 없다. 알 수 없는/깨진 문자열은
 * 견고하게 [ProviderState.OFFLINE] 로 폴백한다(기존 load 의 `runCatching{valueOf}` 견고성 보존).
 */
@Converter(autoApply = false)
class ProviderStateConverter : AttributeConverter<ProviderState, String> {
    override fun convertToDatabaseColumn(attribute: ProviderState?): String = (attribute ?: ProviderState.PENDING).name

    override fun convertToEntityAttribute(dbData: String?): ProviderState =
        dbData?.let {
            runCatching { ProviderState.valueOf(it) }.getOrElse { _ ->
                // 폴백은 견고하지만 알 수 없는 값은 데이터 손상 신호이므로 조용히 넘기지 않는다(예외 원칙 3).
                log.warn("알 수 없는 ProviderState '{}' → OFFLINE 로 폴백(데이터 점검 필요)", it)
                ProviderState.OFFLINE
            }
        } ?: ProviderState.OFFLINE

    private companion object {
        private val log = LoggerFactory.getLogger(ProviderStateConverter::class.java)
    }
}
