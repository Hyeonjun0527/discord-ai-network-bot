package com.discordassistant.central.domain

/**
 * Provider **가용성 스냅샷**의 단일 진실 원천(SSOT).
 *
 * `ProviderCapabilityProfileEntity.providerState`(String 컬럼)에 저장되는 라우팅용 가용성 상태다.
 * relay 생명주기 상태인 [ProviderState] 와는 **다른 개념**(이쪽은 capability 프로필의 간이 가용성)이라
 * 별도 enum 으로 둔다. 이전엔 `"ONLINE"`/`"OFFLINE"`/`"OVERLOADED"`/`"PENDING"`/`"UNKNOWN"` 리터럴이
 * 8개 이상 서비스에 흩어져 비교·생성됐다.
 *
 * 입력은 String 뿐이고 엔티티/DTO/IO 의존이 없으므로 ArchUnit `domainIsIndependent` 를 위반하지 않는다.
 * 저장/비교 문자열([wire])은 기존 대문자 값을 그대로 보존한다(동작·계약 불변).
 */
enum class ProviderAvailability(
    val wire: String,
) {
    UNKNOWN("UNKNOWN"),
    PENDING("PENDING"),
    ONLINE("ONLINE"),
    OFFLINE("OFFLINE"),
    OVERLOADED("OVERLOADED"),
    ;

    companion object {
        /** 저장 문자열 → enum(대소문자 무시). 인식 못 하면 [UNKNOWN]. 기존 `equals(..., ignoreCase=true)` 보존. */
        fun fromWire(value: String?): ProviderAvailability =
            entries.firstOrNull { it.wire.equals(value?.trim(), ignoreCase = true) } ?: UNKNOWN

        /** 라우팅 가용(ONLINE) 여부. 기존 `providerState.equals("ONLINE", ignoreCase=true)` 와 동일. */
        fun isOnline(value: String?): Boolean = fromWire(value) == ONLINE
    }
}
