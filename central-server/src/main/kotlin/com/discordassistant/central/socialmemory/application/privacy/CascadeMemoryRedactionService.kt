package com.discordassistant.central.socialmemory.application.privacy

import com.discordassistant.central.socialmemory.domain.model.episodic.EpisodicMemory
import com.discordassistant.central.socialmemory.domain.model.fact.TemporalFact
import com.discordassistant.central.socialmemory.domain.model.intent.PendingIntent
import com.discordassistant.central.socialmemory.domain.model.relationshipmemory.RelationshipMemory
import com.discordassistant.central.socialmemory.domain.service.redaction.MemoryRedactionCascade
import com.discordassistant.central.socialmemory.domain.service.redaction.RedactionEffect
import org.springframework.stereotype.Service

/**
 * 원천 이벤트 redaction 을 기억 aggregate 들에 **전파**하는 유스케이스(NEXA-P07-T013, deletion-propagation 준수).
 *
 * source event 가 삭제되면([redactedEventIds]) 그 출처에 의존하는 기억을 [MemoryRedactionCascade] 규칙으로 처리한다 —
 * 출처가 모두 사라지면 INVALIDATED, 부분만 남으면 confidence 를 재계산해 약화한다(원문 미보존, ID 차집합만).
 *
 * **acceptance(T013)**: 부분 출처가 남은 기억의 confidence 재계산이 [CascadeResult] 에 반영되고 단위 테스트로 증명된다.
 *
 * 순수 application: 도메인 타입·도메인 규칙만 본다 — JPA/JDA 타입 미참조. ainetwork 호감도는 건드리지 않는다(ADR 0010).
 */
@Service
class CascadeMemoryRedactionService {
    /** 일화 묶음에 redaction 을 전파한 결과. INVALIDATED 는 status 전이, 부분 잔존은 confidence·source 갱신. */
    fun cascadeEpisodic(
        memories: List<EpisodicMemory>,
        redactedEventIds: Set<String>,
    ): CascadeResult<EpisodicMemory> =
        cascade(memories, redactedEventIds, { it.source }, { it.confidence }) { memory, effect ->
            when (effect) {
                is RedactionEffect.Unaffected -> memory
                is RedactionEffect.Invalidated -> memory.copy(status = effect.newStatus)
                is RedactionEffect.Weakened ->
                    memory.copy(source = effect.remainingSource, confidence = effect.recalculatedConfidence)
            }
        }

    /** 사실 묶음에 redaction 전파. */
    fun cascadeFacts(
        facts: List<TemporalFact>,
        redactedEventIds: Set<String>,
    ): CascadeResult<TemporalFact> =
        cascade(facts, redactedEventIds, { it.source }, { it.confidence }) { fact, effect ->
            when (effect) {
                is RedactionEffect.Unaffected -> fact
                is RedactionEffect.Invalidated -> fact.copy(status = effect.newStatus)
                is RedactionEffect.Weakened ->
                    fact.copy(source = effect.remainingSource, confidence = effect.recalculatedConfidence)
            }
        }

    /** 관계 사건 기억 묶음에 redaction 전파. */
    fun cascadeRelationship(
        memories: List<RelationshipMemory>,
        redactedEventIds: Set<String>,
    ): CascadeResult<RelationshipMemory> =
        cascade(memories, redactedEventIds, { it.source }, { it.confidence }) { memory, effect ->
            when (effect) {
                is RedactionEffect.Unaffected -> memory
                is RedactionEffect.Invalidated -> memory.copy(status = effect.newStatus)
                is RedactionEffect.Weakened ->
                    memory.copy(source = effect.remainingSource, confidence = effect.recalculatedConfidence)
            }
        }

    /**
     * 미완 의도 묶음에 redaction 전파. PendingIntent 는 confidence 가 없으므로 부분 잔존이면 source 만 갱신하고
     * (의도는 약화 개념이 약함) 전부 사라지면 INVALIDATED.
     */
    fun cascadeIntents(
        intents: List<PendingIntent>,
        redactedEventIds: Set<String>,
    ): List<PendingIntent> =
        intents.map { intent ->
            val remaining = intent.source.withoutEvents(redactedEventIds)
            when {
                remaining != null && remaining.supportCount == intent.source.supportCount -> intent
                remaining == null -> intent.copy(status = com.discordassistant.central.socialmemory.domain.model.MemoryStatus.INVALIDATED)
                else -> intent.copy(source = remaining)
            }
        }

    private fun <T> cascade(
        items: List<T>,
        redactedEventIds: Set<String>,
        source: (T) -> com.discordassistant.central.socialmemory.domain.model.source.MemorySource,
        confidence: (T) -> com.discordassistant.central.socialmemory.domain.model.Confidence,
        apply: (T, RedactionEffect) -> T,
    ): CascadeResult<T> {
        var invalidated = 0
        var weakened = 0
        val updated =
            items.map { item ->
                val effect = MemoryRedactionCascade.applyRedaction(source(item), confidence(item), redactedEventIds)
                when (effect) {
                    is RedactionEffect.Invalidated -> invalidated++
                    is RedactionEffect.Weakened -> weakened++
                    RedactionEffect.Unaffected -> Unit
                }
                apply(item, effect)
            }
        return CascadeResult(memories = updated, invalidatedCount = invalidated, weakenedCount = weakened)
    }
}

/**
 * redaction cascade 전파 결과(NEXA-P07-T013). [memories] 는 갱신된 기억, [invalidatedCount] 는 무효화 수,
 * [weakenedCount] 는 부분 출처 잔존으로 confidence 가 재계산된 수.
 */
data class CascadeResult<T>(
    val memories: List<T>,
    val invalidatedCount: Int,
    val weakenedCount: Int,
)
