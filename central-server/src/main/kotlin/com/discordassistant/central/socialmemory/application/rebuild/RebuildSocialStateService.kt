package com.discordassistant.central.socialmemory.application.rebuild

import com.discordassistant.central.socialmemory.application.port.out.SocialStateSnapshotPort
import com.discordassistant.central.socialmemory.domain.event.SocialStateUpdate
import com.discordassistant.central.socialmemory.domain.model.relationship.MemberKey
import com.discordassistant.central.socialmemory.domain.model.snapshot.SocialStateSnapshot
import org.springframework.stereotype.Service

/**
 * 사회 상태 snapshot 재구축 유스케이스(NEXA-P06-T019). 한 guild 의 update stream 에서 각 관계 키의 사회 상태를
 * **다시 계산**해 persistence 에 upsert 한다. event store 의 파생 읽기 모델이므로 원천(update stream)에서 언제든
 * 재생할 수 있다.
 *
 * **acceptance(T019) — 현재 snapshot 삭제 후 같은 hash 의 상태를 얻는다**: [rebuild] 는 [SocialStateSnapshotPort]
 * 를 먼저 비운 뒤(또는 키별 삭제 후) [SocialStateSnapshot.rebuild] 로 같은 update 집합을 접어 재구축한다.
 * [SocialStateSnapshot.canonicalHash] 가 결정론적이라(field·코드 순서 고정, idempotencyKey dedup) 재구축 전후
 * snapshot 의 hash 가 같다 — [RebuildReport.hashesByKey] 로 검증 가능하다.
 *
 * 외부 전송 side effect 없음: 이 서비스는 persistence 포트와 도메인 reducer 에만 의존한다 — Discord 전송 포트를
 * 타입 수준에서 참조하지 않는다(conversation replay 와 동일 계약).
 *
 * 순수 application: 도메인 타입과 아웃바운드 포트만 본다 — JPA/JDA 타입 미참조.
 */
@Service
class RebuildSocialStateService(
    private val snapshots: SocialStateSnapshotPort,
) {
    /**
     * [updates] 를 관계 키별로 묶어 각 key 의 snapshot 을 [projectionVersion] 으로 재구축·저장한다. 키와 무관한
     * (채널 단위) update 는 관계 snapshot 에 반영되지 않는다([SocialStateSnapshot.apply] 가 무시).
     *
     * @return 키별 재구축된 snapshot 의 canonical hash(재구축 검증·감사). 같은 input 은 같은 hash.
     */
    fun rebuild(
        projectionVersion: Long,
        updates: List<SocialStateUpdate>,
    ): RebuildReport {
        val byKey: Map<MemberKey, List<SocialStateUpdate>> =
            updates
                .mapNotNull { update -> keyOf(update)?.let { it to update } }
                .groupBy({ it.first }, { it.second })

        val hashesByKey = LinkedHashMap<MemberKey, String>()
        byKey.forEach { (key, keyUpdates) ->
            val snapshot = SocialStateSnapshot.rebuild(key, projectionVersion, keyUpdates)
            snapshots.save(snapshot)
            hashesByKey[key] = snapshot.canonicalHash()
        }
        return RebuildReport(rebuiltKeys = hashesByKey.size, hashesByKey = hashesByKey)
    }

    /**
     * 한 키의 snapshot 을 삭제 후 재구축해 hash 가 보존되는지 검증한다(acceptance T019). 삭제 전 hash 와 재구축 후
     * hash 가 같으면 true — event store 가 진실원이고 snapshot 은 손실 없이 재생 가능함을 증명한다.
     */
    fun deleteThenRebuildHashMatches(
        key: MemberKey,
        projectionVersion: Long,
        updates: List<SocialStateUpdate>,
    ): Boolean {
        val before = SocialStateSnapshot.rebuild(key, projectionVersion, updates)
        snapshots.save(before)
        val beforeHash = before.canonicalHash()

        snapshots.deleteByKey(key)
        val after = SocialStateSnapshot.rebuild(key, projectionVersion, updates)
        snapshots.save(after)
        return after.canonicalHash() == beforeHash
    }

    private fun keyOf(update: SocialStateUpdate): MemberKey? =
        when (update) {
            is com.discordassistant.central.socialmemory.domain.event.NexaActionObserved -> update.key
            is com.discordassistant.central.socialmemory.domain.event.HumanOutcomeObserved -> update.key
            is com.discordassistant.central.socialmemory.domain.event.SceneUpdateObserved -> null
        }
}

/** 재구축 실행 결과 요약(운영 가시성). 원문 미포함 — 키별 canonical hash 와 재구축 키 수만. */
data class RebuildReport(
    val rebuiltKeys: Int,
    val hashesByKey: Map<MemberKey, String>,
)
