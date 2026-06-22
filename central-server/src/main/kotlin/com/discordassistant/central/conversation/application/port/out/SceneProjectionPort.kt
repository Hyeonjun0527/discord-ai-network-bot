package com.discordassistant.central.conversation.application.port.out

import com.discordassistant.central.conversation.domain.model.scene.ConversationScene

/**
 * 장면 projection persistence 의 아웃바운드 포트(NEXA-P05-T020, 헥사고날). 현재 장면 snapshot 과 version history 의
 * **최소 메타데이터** 를 읽기 모델로 저장한다. 구현 어댑터(JPA)는 adapter.outbound.persistence.scene 에 둔다.
 *
 * 순수성 경계: application 레이어 소속이라 도메인 타입([ConversationScene])과 표준 타입만 본다 — Spring/JPA 타입을
 * 참조하지 않는다(어댑터가 채운다).
 *
 * **acceptance(T020) — snapshot 삭제 후 event store 로 재구축**: 이 projection 은 event store 의 **파생 읽기 모델**
 * 이다. [deleteAll] 로 전부 비운 뒤 event store 를 재생하며 [save] 를 다시 호출하면 동일 snapshot 이 재구축된다.
 * [save] 는 채널당 1행 upsert(멱등)라 같은 채널을 N 번 저장해도 한 snapshot 으로 수렴한다.
 *
 * 원문 비저장(logging-boundary.md): snapshot 은 식별자·계산 메타만 — burst/thread 식별자 수, contextVersion,
 * sceneSeq 만 보관한다. 원문/파생 텍스트가 평문으로 남지 않는다(SceneUpdated PII medium).
 */
interface SceneProjectionPort {
    /**
     * 현재 장면 snapshot 을 저장하고(채널당 1행 upsert), version history 에 이 sceneSeq 메타를 남긴다.
     * 같은 채널을 다시 저장하면 snapshot 은 갱신되고 history 는 sceneSeq 마다 한 행으로 누적된다(멱등).
     */
    fun save(scene: ConversationScene)

    /** [channelId] 현재 snapshot 을 돌려준다(없으면 null). 재구축 검증·읽기용. */
    fun findByChannel(channelId: Long): SceneSnapshotRecord?

    /** [channelId] 의 version history(sceneSeq 오름차순). snapshot 삭제 후 재구축·감사용. */
    fun history(channelId: Long): List<SceneVersionRecord>

    /** projection 전체 삭제(replay 재구축 전 초기화, acceptance T020). */
    fun deleteAll()
}

/**
 * 장면 snapshot 조회 view(원문 미포함, PII medium). 식별자 수·버전·순번 등 **최소 메타** 만 — combined text 가
 * 아니라 식별자 참조 요약만 운반한다(원문은 event store 에만, T020).
 */
data class SceneSnapshotRecord(
    val guildId: Long,
    val channelId: Long,
    val sceneSeq: Long,
    val contextVersion: Long,
    /** 장면이 참조하는 최근 burst 수(원문 비포함 — 식별자 개수만). */
    val recentBurstCount: Int,
    /** 활성 논리 스레드 수(graph ref 개수). */
    val activeThreadCount: Int,
    /** 참여자 수. */
    val participantCount: Int,
)

/**
 * 장면 version history 한 행(원문 미포함, PII medium). 각 sceneSeq 갱신의 최소 메타 — 어느 순번에서 contextVersion 이
 * 어떻게 바뀌었는지만 보관한다(재구축·감사).
 */
data class SceneVersionRecord(
    val channelId: Long,
    val sceneSeq: Long,
    val contextVersion: Long,
)
