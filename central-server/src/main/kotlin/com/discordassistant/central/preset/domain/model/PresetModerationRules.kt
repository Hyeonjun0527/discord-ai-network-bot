package com.discordassistant.central.preset.domain.model

/**
 * 공개 프리셋 모더레이션 큐의 **순수 결정 규칙**.
 *
 * `PresetCatalogQueryService` 의 `presetModerationAction`/`moderationSeverityRank` 중
 * 입력이 primitive(status: String, riskCodes: List<String>)뿐인 부분만 도메인으로 추출했다.
 * 엔티티/DTO 의존이 없어 `domainIsIndependent` 를 위반하지 않는다.
 *
 * `moderationItem`(PublishedPresetEntity 를 받음)·`presetModerationNextActions`(DTO 리스트를 받음)는
 * primitive-only 가 아니므로 서비스에 남기고, 그 내부의 순수 규칙 호출만 여기로 위임한다.
 * 추천 액션 문자열·정렬 순위 값은 기존과 동일하므로 동작/응답 JSON 불변.
 */
object PresetModerationRules {
    /**
     * 게시 상태 + 위험 코드 목록으로 추천 모더레이션 액션 문자열을 결정한다.
     * 기존 `PresetCatalogQueryService.presetModerationAction(status, riskCodes)` 와 동일.
     */
    fun recommendedAction(
        status: String,
        riskCodes: List<String>,
    ): String =
        when {
            status == "removed" -> "removed 상태를 유지하고 카탈로그에는 노출하지 마세요."
            status == "suspended" -> "검수자가 수정 요청 또는 제거 결정을 내려야 합니다."
            "popular_reported" in riskCodes -> "인기 프리셋이 신고됐으므로 우선 검토하고 필요하면 일시 중단하세요."
            "reported" in riskCodes -> "신고 사유를 확인하고 dismiss/suspend/remove 중 하나로 처리하세요."
            "high_safety_level" in riskCodes -> "높은 안전 등급 프리셋은 게시 설명과 행동 스냅샷을 수동 검토하세요."
            else -> "추가 조치가 필요 없습니다."
        }

    /**
     * 모더레이션 큐 정렬 우선순위(낮을수록 먼저). 기존 `moderationSeverityRank(item)` 가
     * `item.status`/`item.riskCodes` 만 보던 부분을 primitive 시그니처로 추출했다.
     */
    fun severityRank(
        status: String,
        riskCodes: List<String>,
    ): Int =
        when {
            status == "under_review" -> 0
            "popular_reported" in riskCodes -> 1
            status == "suspended" -> 2
            "reported" in riskCodes -> 3
            "high_safety_level" in riskCodes -> 4
            else -> 5
        }
}
