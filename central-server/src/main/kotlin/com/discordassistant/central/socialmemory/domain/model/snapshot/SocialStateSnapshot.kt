package com.discordassistant.central.socialmemory.domain.model.snapshot

import com.discordassistant.central.socialmemory.domain.event.HumanOutcomeObserved
import com.discordassistant.central.socialmemory.domain.event.NexaActionKind
import com.discordassistant.central.socialmemory.domain.event.NexaActionObserved
import com.discordassistant.central.socialmemory.domain.event.SocialStateUpdate
import com.discordassistant.central.socialmemory.domain.model.relationship.InteractionOutcome
import com.discordassistant.central.socialmemory.domain.model.relationship.MemberInteractionState
import com.discordassistant.central.socialmemory.domain.model.relationship.MemberKey
import java.security.MessageDigest
import java.time.Instant

/**
 * 한 관계 키([MemberKey])의 사회 상태 snapshot(NEXA-P06-T018/T019, 순수 도메인 값 객체·불변).
 *
 * event store 에서 재계산되는 **파생 읽기 모델**이다 — NEXA↔사람 교환 통계([interaction])와 관찰된 결과 카운트
 * ([outcomeCounts]), 그리고 **source watermark**([lastSourceEventId]/[lastObservedAt]/[projectionVersion])를
 * 담는다. 원문은 담지 않는다(observable-state-policy 불변식 1, data-categories.md) — 카운트·코드·식별자·시각만.
 *
 * **acceptance(T018) — 원본 content 없이 재생용 source watermark 를 보존한다**: snapshot 은 마지막으로 적용한
 * 원천 이벤트 ID([lastSourceEventId])와 시각, projection version 만 보관한다(content 없음). watermark 로 재생 시작점·
 * 결정론을 보장한다.
 *
 * **acceptance(T019) — snapshot 삭제 후 같은 hash 의 상태를 얻는다**: [canonicalHash] 는 관찰 상태를 정렬된 정규
 * 표현으로 직렬화해 SHA-256 한 값이다. 같은 update 시퀀스를 같은 projectionVersion 으로 재생([rebuild])하면 항상
 * 같은 hash 가 나온다(map 순서·field 순서 결정론).
 *
 * 순수성: Spring/JPA/JDA·ainetwork 엔티티 미참조. 표준 java.time·java.security 만 쓴다.
 */
data class SocialStateSnapshot(
    /** 이 snapshot 이 속한 guild-scoped 가명 관계 키. */
    val key: MemberKey,
    /** NEXA↔사람 교환 통계(burst 카운트·최근성). [MemberInteractionState] 재사용(SSOT). */
    val interaction: MemberInteractionState,
    /** 관찰된 결과 코드별 누적 카운트(원문 없음 — 닫힌 코드 카운트). */
    val outcomeCounts: Map<InteractionOutcome, Int>,
    /** 이 snapshot 을 만든 projection 규칙 버전(재투영 비교). */
    val projectionVersion: Long,
    /** 마지막으로 적용한 원천 이벤트 ID(재생 watermark). 아직 없으면 null. */
    val lastSourceEventId: String?,
    /** 마지막 적용 시각(감쇠 기준·watermark). 아직 없으면 null. */
    val lastObservedAt: Instant?,
) {
    init {
        require(projectionVersion >= 0) { "projectionVersion 은 음수일 수 없다" }
        require(outcomeCounts.values.all { it >= 0 }) { "outcome 카운트는 음수일 수 없다" }
    }

    /**
     * 한 [SocialStateUpdate] 를 접어 갱신한 새 snapshot. [NexaActionObserved] 는 reciprocity 방향에 따라
     * 교환 카운트를, [HumanOutcomeObserved] 는 결과 카운트를 올린다. 키가 다른 update 는 무시한다(다른 관계).
     * watermark(마지막 이벤트 ID·시각)를 update 의 것으로 전진시킨다.
     */
    fun apply(update: SocialStateUpdate): SocialStateSnapshot =
        when (update) {
            is NexaActionObserved -> applyAction(update)
            is HumanOutcomeObserved -> applyOutcome(update)
            else -> this // SceneUpdateObserved 는 관계 키 단위가 아니라 채널 단위 — 이 snapshot 에 영향 없음.
        }

    private fun applyAction(update: NexaActionObserved): SocialStateSnapshot {
        if (update.key != key) return this
        val next =
            when (update.action) {
                NexaActionKind.ADDRESSED_MEMBER -> interaction.recordNexaToMember(update.observedAt)
                NexaActionKind.RESPONDED_TO_MEMBER -> interaction.recordMemberToNexa(update.observedAt)
            }
        return copy(interaction = next, lastSourceEventId = update.sourceEventIds.last(), lastObservedAt = update.observedAt)
    }

    private fun applyOutcome(update: HumanOutcomeObserved): SocialStateSnapshot {
        if (update.key != key) return this
        val next = outcomeCounts + (update.outcome to ((outcomeCounts[update.outcome] ?: 0) + 1))
        return copy(outcomeCounts = next, lastSourceEventId = update.sourceEventIds.last(), lastObservedAt = update.observedAt)
    }

    /**
     * 관찰 상태의 결정론적 정규 직렬화를 SHA-256 한 hash(소문자 hex). field·outcome 코드 순서가 고정돼 같은 상태는
     * 항상 같은 hash 다(acceptance T019 — 재구축 검증). watermark·projectionVersion 도 포함해 재투영을 구분한다.
     */
    fun canonicalHash(): String {
        val sb = StringBuilder()
        sb
            .append("key=")
            .append(key.guildPseudonym)
            .append('/')
            .append(key.memberPseudonym)
            .append('\n')
        sb.append("projectionVersion=").append(projectionVersion).append('\n')
        sb.append("nexaToMember=").append(interaction.nexaToMemberBursts).append('\n')
        sb.append("memberToNexa=").append(interaction.memberToNexaBursts).append('\n')
        sb.append("observedReactions=").append(interaction.observedReactions).append('\n')
        sb.append("lastInteractionAt=").append(interaction.lastInteractionAt).append('\n')
        // outcome 코드를 wireName 으로 정렬해 map 순서에 의존하지 않는다(결정론).
        InteractionOutcome.entries.sortedBy { it.wireName }.forEach { code ->
            sb
                .append("outcome:")
                .append(code.wireName)
                .append('=')
                .append(outcomeCounts[code] ?: 0)
                .append('\n')
        }
        sb.append("lastSourceEventId=").append(lastSourceEventId).append('\n')
        sb.append("lastObservedAt=").append(lastObservedAt).append('\n')
        val digest = MessageDigest.getInstance("SHA-256").digest(sb.toString().toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    companion object {
        /** 아직 아무 update 도 적용되지 않은 빈 snapshot(주어진 키·projection version). */
        fun empty(
            key: MemberKey,
            projectionVersion: Long,
        ): SocialStateSnapshot =
            SocialStateSnapshot(
                key = key,
                interaction = MemberInteractionState.empty(key),
                outcomeCounts = emptyMap(),
                projectionVersion = projectionVersion,
                lastSourceEventId = null,
                lastObservedAt = null,
            )

        /**
         * [updates] 를 **canonical 순서**(idempotencyKey dedup 후 observedAt·idempotencyKey 정렬)로 접어 snapshot 을
         * 재구축한다(NEXA-P06-T019 reducer). 같은 update 집합은 도착 순서·중복과 무관하게 같은 snapshot·같은 hash 다.
         * 키가 다른 update 는 [apply] 에서 무시된다(이 관계와 무관).
         */
        fun rebuild(
            key: MemberKey,
            projectionVersion: Long,
            updates: List<SocialStateUpdate>,
        ): SocialStateSnapshot {
            val canonical =
                updates
                    .filter { it.projectionVersion == projectionVersion }
                    .associateBy { it.idempotencyKey }
                    .values
                    .sortedWith(compareBy({ it.observedAt }, { it.idempotencyKey }))
            return canonical.fold(empty(key, projectionVersion)) { acc, update -> acc.apply(update) }
        }
    }
}
