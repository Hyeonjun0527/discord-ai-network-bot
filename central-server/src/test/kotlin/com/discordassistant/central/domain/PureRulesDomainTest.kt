package com.discordassistant.central.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 서비스에서 추출한 순수 결정 규칙(FanoutLoadRisk/OverloadRisk/PresetModerationRules) 단위 테스트.
 *
 * 추출 전 서비스 구현과 임계값/와이어 값/정렬 순위가 동일함을 고정한다(동작 불변 보장).
 */
class PureRulesDomainTest {
    // --- FanoutLoadRisk ---

    @Test
    fun `fanout load risk wire 값과 등급 임계값을 보존한다`() {
        assertEquals("normal", FanoutLoadRisk.NORMAL.wire)
        assertEquals("watch", FanoutLoadRisk.WATCH.wire)
        assertEquals("high", FanoutLoadRisk.HIGH.wire)
        assertEquals("critical", FanoutLoadRisk.CRITICAL.wire)

        // 빈 후보 → normal
        assertEquals(FanoutLoadRisk.NORMAL, FanoutLoadRisk.classify(0, 0, 0, emptyList()))
        // timeoutRate >= 0.5 → critical
        assertEquals(FanoutLoadRisk.CRITICAL, FanoutLoadRisk.classify(4, 2, 0, emptyList()))
        // failureRate >= 0.75 → critical
        assertEquals(FanoutLoadRisk.CRITICAL, FanoutLoadRisk.classify(4, 1, 2, emptyList()))
        // timeoutRate >= 0.25 → high
        assertEquals(FanoutLoadRisk.HIGH, FanoutLoadRisk.classify(4, 1, 0, emptyList()))
        // averageLatency >= 10_000 → high
        assertEquals(FanoutLoadRisk.HIGH, FanoutLoadRisk.classify(2, 0, 0, listOf(10_000, 11_000)))
        // candidateCount >= 5 → watch
        assertEquals(FanoutLoadRisk.WATCH, FanoutLoadRisk.classify(5, 0, 0, emptyList()))
        // averageLatency >= 5_000 → watch
        assertEquals(FanoutLoadRisk.WATCH, FanoutLoadRisk.classify(2, 0, 0, listOf(5_000, 6_000)))
        // 정상
        assertEquals(FanoutLoadRisk.NORMAL, FanoutLoadRisk.classify(2, 0, 0, listOf(100, 200)))
    }

    @Test
    fun `fanout load risk rank 는 기존 riskRank 정수 매핑을 보존한다`() {
        assertEquals(4, FanoutLoadRisk.rankOf("critical"))
        assertEquals(3, FanoutLoadRisk.rankOf("HIGH"))
        assertEquals(2, FanoutLoadRisk.rankOf("watch"))
        assertEquals(1, FanoutLoadRisk.rankOf("normal"))
        // 알 수 없는 값은 normal(1)
        assertEquals(1, FanoutLoadRisk.rankOf("__nope__"))
    }

    // --- OverloadRisk ---

    @Test
    fun `overload risk 정규화와 와이어 값을 보존한다`() {
        assertEquals(OverloadRisk.NORMAL, OverloadRisk.normalize(null))
        assertEquals(OverloadRisk.NORMAL, OverloadRisk.normalize("  "))
        assertEquals(OverloadRisk.CRITICAL, OverloadRisk.normalize("CRITICAL"))
        assertEquals(OverloadRisk.HIGH, OverloadRisk.normalize("high"))
        assertEquals(OverloadRisk.LOW, OverloadRisk.normalize("low"))
        // overload/overloaded → high
        assertEquals(OverloadRisk.HIGH, OverloadRisk.normalize("overload"))
        assertEquals(OverloadRisk.HIGH, OverloadRisk.normalize("overloaded"))
        // 알 수 없는 값 → normal
        assertEquals(OverloadRisk.NORMAL, OverloadRisk.normalize("weird"))

        assertEquals("normal", OverloadRisk.NORMAL.wire)
        assertEquals("low", OverloadRisk.LOW.wire)
        assertEquals("high", OverloadRisk.HIGH.wire)
        assertEquals("critical", OverloadRisk.CRITICAL.wire)
    }

    @Test
    fun `overload risk 판정과 severityRank 를 보존한다`() {
        assertTrue(OverloadRisk.isOverloadRisk("high"))
        assertTrue(OverloadRisk.isOverloadRisk("critical"))
        assertTrue(OverloadRisk.isOverloadRisk("overloaded"))
        assertFalse(OverloadRisk.isOverloadRisk("normal"))
        assertFalse(OverloadRisk.isOverloadRisk("low"))
        assertFalse(OverloadRisk.isOverloadRisk(null))

        assertEquals(3, OverloadRisk.CRITICAL.severityRank)
        assertEquals(2, OverloadRisk.HIGH.severityRank)
        assertEquals(1, OverloadRisk.LOW.severityRank)
        assertEquals(0, OverloadRisk.NORMAL.severityRank)
    }

    // --- PresetModerationRules ---

    @Test
    fun `preset moderation 추천 액션 우선순위를 보존한다`() {
        assertEquals(
            "removed 상태를 유지하고 카탈로그에는 노출하지 마세요.",
            PresetModerationRules.recommendedAction("removed", listOf("reported")),
        )
        assertEquals(
            "검수자가 수정 요청 또는 제거 결정을 내려야 합니다.",
            PresetModerationRules.recommendedAction("suspended", emptyList()),
        )
        assertEquals(
            "인기 프리셋이 신고됐으므로 우선 검토하고 필요하면 일시 중단하세요.",
            PresetModerationRules.recommendedAction("published", listOf("reported", "popular_reported")),
        )
        assertEquals(
            "신고 사유를 확인하고 dismiss/suspend/remove 중 하나로 처리하세요.",
            PresetModerationRules.recommendedAction("published", listOf("reported")),
        )
        assertEquals(
            "높은 안전 등급 프리셋은 게시 설명과 행동 스냅샷을 수동 검토하세요.",
            PresetModerationRules.recommendedAction("published", listOf("high_safety_level")),
        )
        assertEquals(
            "추가 조치가 필요 없습니다.",
            PresetModerationRules.recommendedAction("published", emptyList()),
        )
    }

    @Test
    fun `preset moderation severity rank 우선순위를 보존한다`() {
        assertEquals(0, PresetModerationRules.severityRank("under_review", emptyList()))
        assertEquals(1, PresetModerationRules.severityRank("published", listOf("popular_reported")))
        assertEquals(2, PresetModerationRules.severityRank("suspended", emptyList()))
        assertEquals(3, PresetModerationRules.severityRank("published", listOf("reported")))
        assertEquals(4, PresetModerationRules.severityRank("published", listOf("high_safety_level")))
        assertEquals(5, PresetModerationRules.severityRank("published", emptyList()))
        // under_review 가 popular_reported 보다 우선
        assertEquals(0, PresetModerationRules.severityRank("under_review", listOf("popular_reported")))
    }
}
