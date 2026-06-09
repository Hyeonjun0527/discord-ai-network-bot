package com.discordassistant.central.requestlog.adapter.outbound.persistence

import com.discordassistant.central.shared.RequestState
import jakarta.persistence.AttributeConverter
import jakarta.persistence.Converter
import org.slf4j.LoggerFactory

/**
 * `RequestState` ↔ DB VARCHAR(enum.name) 변환기 (#121 `ProviderStateConverter` 와 동일 패턴).
 *
 * DB 컬럼은 그대로 문자열(enum 이름)이라 스키마 마이그레이션이 필요 없다. 알 수 없는/깨진 문자열은
 * 견고하게 [RequestState.RECEIVED] 로 폴백한다(기존 String 기본값 `"RECEIVED"` 의미 보존).
 */
@Converter(autoApply = false)
class RequestStateConverter : AttributeConverter<RequestState, String> {
    override fun convertToDatabaseColumn(attribute: RequestState?): String = (attribute ?: RequestState.RECEIVED).name

    override fun convertToEntityAttribute(dbData: String?): RequestState =
        dbData?.let {
            runCatching { RequestState.valueOf(it) }.getOrElse { _ ->
                // 폴백 자체는 견고하지만, 알 수 없는 값은 데이터 손상 신호이므로 조용히 넘기지 않는다(예외 원칙 3).
                log.warn("알 수 없는 RequestState '{}' → RECEIVED 로 폴백(데이터 점검 필요)", it)
                RequestState.RECEIVED
            }
        } ?: RequestState.RECEIVED

    private companion object {
        private val log = LoggerFactory.getLogger(RequestStateConverter::class.java)
    }
}
