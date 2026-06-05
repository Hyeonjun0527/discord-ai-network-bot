package com.discordassistant.central.shared

/**
 * 모델/Provider 품질 등급의 **단일 진실 원천(SSOT)**.
 *
 * 이전엔 같은 등급 매핑이 서비스마다 손수 복제돼 있었다:
 * - `qualityRank(value)` (ChannelAiRoutingPolicyService / AiNetworkDashboardQueryService 동일 복제)
 * - `qualityTierName(rank)` (ChannelAiRoutingPolicyService)
 * - `qualityBonus(tier)` (ProviderRouter)
 * - `inferQualityTier(...)` 가 만든 리터럴, `== "specialized"` 직접 비교 등
 *
 * 모든 등급·순위·라우팅 가중치·와이어 문자열을 이 enum 하나로 모은다. 입력은 String/primitive 뿐이고
 * 엔티티/DTO/IO 의존이 없으므로 ArchUnit `domainIsIndependent` 를 위반하지 않는다.
 *
 * 와이어/저장 문자열([wire])은 기존 값을 그대로 보존한다(가장 낮은 등급은 `"unknown"`).
 */
enum class ModelQualityTier(
    /** 응답 JSON·capability 프로필 컬럼에 쓰는 소문자 와이어 값. 동작/계약 보존. */
    val wire: String,
    /** 정렬·최소품질 비교용 순위(높을수록 고품질). 기존 `qualityRank` 와 동일한 정수. */
    val rank: Int,
    /** 라우팅 점수 가산치. 기존 `ProviderRouter.qualityBonus` 와 동일. */
    val routingBonus: Double,
) {
    UNKNOWN("unknown", 0, 0.0),
    STANDARD("standard", 1, 0.3),
    HIGH("high", 2, 1.0),
    SPECIALIZED("specialized", 3, 1.5),
    ;

    companion object {
        /** 임의의 등급 문자열 → enum. 인식 못 하는 값(빈 값 포함)은 [UNKNOWN]. */
        fun fromWire(value: String?): ModelQualityTier = entries.firstOrNull { it.wire == value?.trim()?.lowercase() } ?: UNKNOWN

        /** 등급 문자열의 순위. 기존 `qualityRank(value)` 보존. */
        fun rankOf(value: String?): Int = fromWire(value).rank

        /** 순위 → 등급 enum. 매칭 없으면 [UNKNOWN]. 기존 `qualityTierName(rank)` 의 역할. */
        fun ofRank(rank: Int): ModelQualityTier = entries.firstOrNull { it.rank == rank } ?: UNKNOWN
    }
}
