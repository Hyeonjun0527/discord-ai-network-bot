package com.discordassistant.central.provider.domain.model

/**
 * 프로바이더가 특정 모델을 **누구에게** 제공할지의 허용 범위(`/provider-scope` 의 `role` 옵션).
 *
 * 이전엔 `all`/`trusted`/`admin` 문자열이 슬래시 카탈로그에 하드코딩되고 `ContributionPolicyService`
 * 가 그 값을 raw 로 저장만 했다. 값 집합과 표시 라벨, 정규화를 이 enum 으로 단일화한다(SSOT).
 * 와이어/DB 표현은 기존 소문자 값([wire])을 그대로 유지 — 마이그레이션 불필요.
 */
enum class ProviderModelScope(
    val wire: String,
    /** Discord 슬래시 옵션 등 사용자 표시용 한글 라벨. */
    val label: String,
) {
    /** 모두에게 제공(기본). */
    ALL("all", "모두"),

    /** 신뢰 역할에게만 제공. */
    TRUSTED("trusted", "신뢰 역할"),

    /** 관리자에게만 제공. */
    ADMIN("admin", "관리자만"),
    ;

    companion object {
        /** 슬래시 옵션 choice 용 (라벨, 와이어값) 목록 — SSOT. */
        fun slashChoices(): List<Pair<String, String>> = entries.map { it.label to it.wire }

        private val BY_WIRE: Map<String, ProviderModelScope> = entries.associateBy { it.wire }

        /** 소문자 와이어 문자열 → enum. 알 수 없는/빈 값은 견고하게 [ALL] 로 폴백(기본 허용범위). */
        fun fromWire(value: String?): ProviderModelScope = value?.trim()?.lowercase()?.let { BY_WIRE[it] } ?: ALL
    }
}
