package com.discordassistant.central.socialmemory.domain.service.redaction

import com.discordassistant.central.socialmemory.domain.model.Confidence
import com.discordassistant.central.socialmemory.domain.model.MemoryStatus
import com.discordassistant.central.socialmemory.domain.model.source.MemorySource

/**
 * 원천 이벤트 redaction(삭제)이 기억에 미치는 **cascade** 규칙(NEXA-P07-T013, 순수 도메인 서비스·deletion-propagation).
 *
 * 삭제는 원본→파생으로 전파된다(deletion-propagation 불변식 1). 한 기억의 출처([MemorySource]) 중 일부/전부가 삭제될 때:
 * - **모든 출처가 사라지면** → 기억을 [MemoryStatus.INVALIDATED] 로(근거 소멸, retrieval 제외).
 * - **부분 출처가 남으면** → 기억을 유지하되 남은 근거 비율로 **confidence 를 재계산**(줄임). 원문은 보존하지 않으므로
 *   ID 차집합과 비율만으로 판정한다.
 *
 * **acceptance(T013) — 부분 출처가 남은 기억의 confidence 재계산 규칙이 테스트된다**: [applyRedaction] 이 남은 출처
 * 비율(remaining/before)을 곱해 confidence 를 낮춘 결과를 돌려주고, 이를 단위 테스트로 증명한다.
 *
 * 순수성: Spring/JPA/JDA 미참조.
 */
object MemoryRedactionCascade {
    /**
     * [source] 에서 [redactedEventIds] 를 제거하고, 그 영향을 [RedactionEffect] 로 돌려준다.
     * - 영향 없음(겹치는 출처 없음): [RedactionEffect.Unaffected].
     * - 전부 제거: [RedactionEffect.Invalidated](status→INVALIDATED).
     * - 부분 제거: [RedactionEffect.Weakened](남은 source + 재계산 confidence).
     */
    fun applyRedaction(
        source: MemorySource,
        confidence: Confidence,
        redactedEventIds: Set<String>,
    ): RedactionEffect {
        val before = source.supportCount
        val remainingSource = source.withoutEvents(redactedEventIds)

        // 겹치는 출처가 없으면 영향 없음(어떤 출처도 제거되지 않음).
        if (remainingSource != null && remainingSource.supportCount == before) {
            return RedactionEffect.Unaffected
        }

        if (remainingSource == null) {
            // 근거 전부 소멸 — 기억 무효화(retrieval 제외, lineage 보존).
            return RedactionEffect.Invalidated(newStatus = MemoryStatus.INVALIDATED)
        }

        // 부분 출처 잔존 — 남은 근거 비율로 confidence 재계산(줄임). before>0 보장(supportCount>=1).
        val ratio = remainingSource.supportCount.toDouble() / before.toDouble()
        val recalculated = Confidence((confidence.value * ratio).coerceIn(0.0, 1.0))
        return RedactionEffect.Weakened(remainingSource = remainingSource, recalculatedConfidence = recalculated)
    }
}

/** redaction cascade 의 영향(NEXA-P07-T013). */
sealed interface RedactionEffect {
    /** 삭제된 출처가 이 기억과 무관 — 변화 없음. */
    data object Unaffected : RedactionEffect

    /** 모든 출처가 삭제됨 — 기억 무효화(status→INVALIDATED, retrieval 제외). */
    data class Invalidated(
        val newStatus: MemoryStatus,
    ) : RedactionEffect

    /** 부분 출처만 삭제됨 — 기억 유지, 남은 근거 비율로 confidence 재계산(줄임). */
    data class Weakened(
        val remainingSource: MemorySource,
        val recalculatedConfidence: Confidence,
    ) : RedactionEffect
}
