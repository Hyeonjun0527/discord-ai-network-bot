package com.discordassistant.central.domain

/**
 * Provider 과부하 위험 등급 (Provider 보호/라우팅).
 *
 * `ProviderSafetyService` 에 String 확장(`normalizedRisk`/`isOverloadRisk`/`severityRank`)으로
 * 흩어져 있던 **순수 분류/판정 규칙**을 도메인 enum 으로 승격했다. 입력은 String/primitive 뿐이고
 * 엔티티/DTO/IO 의존이 없으므로 `domainIsIndependent` 를 위반하지 않는다.
 *
 * `ProviderCapabilityProfileEntity.overloadRisk`(String 컬럼) 자체는 그대로 두고, 서비스가
 * String 을 [normalize] 로 정규화한 뒤 도메인 규칙을 쓴다. 응답 JSON 의 `risk` 필드는 소문자
 * 와이어 값([wire]) 을 유지하므로 동작/계약 불변.
 */
enum class OverloadRisk(
    val wire: String,
    /** 정렬 우선순위(높을수록 위험). 기존 `severityRank()` 와 동일한 정수 매핑. */
    val severityRank: Int,
) {
    NORMAL("normal", 0),
    LOW("low", 1),
    HIGH("high", 2),
    CRITICAL("critical", 3),
    ;

    /** 과부하로 보호해야 하는 등급인가(high/critical). 기존 `isOverloadRisk()` 와 동일. */
    val isOverload: Boolean get() = this == HIGH || this == CRITICAL

    companion object {
        /**
         * 임의의 risk 문자열을 정규화한다. 기존 `String.normalizedRisk()` 와 동일하게
         * - 공백/빈 값 → [NORMAL]
         * - `overload`/`overloaded` → [HIGH]
         * - 인식 못 하는 값 → [NORMAL]
         */
        fun normalize(value: String?): OverloadRisk =
            when (
                value
                    ?.trim()
                    ?.lowercase()
                    .orEmpty()
                    .ifBlank { "normal" }
            ) {
                "critical" -> CRITICAL
                "high" -> HIGH
                "normal" -> NORMAL
                "low" -> LOW
                "overload", "overloaded" -> HIGH
                else -> NORMAL
            }

        /** 정규화 후 과부하 위험인지 판정. 기존 `String.isOverloadRisk()` 보존. */
        fun isOverloadRisk(value: String?): Boolean = normalize(value).isOverload
    }
}
