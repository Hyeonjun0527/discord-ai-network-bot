package com.discordassistant.central.conversation.application.port.out

import com.discordassistant.central.conversation.domain.model.burst.BurstId
import com.discordassistant.central.conversation.domain.model.burst.BurstStatus
import com.discordassistant.central.conversation.domain.model.burst.UtteranceBurst

/**
 * 버스트 projection persistence 의 아웃바운드 포트(NEXA-P04-T018, 헥사고날). finalize/교정된 버스트와 그 fragment 연결,
 * revision, segmentation version 을 읽기 모델로 저장한다. 구현 어댑터(JPA)는 adapter.outbound.persistence.burst 에 둔다.
 *
 * 순수성 경계: application 레이어 소속이라 도메인 타입([UtteranceBurst]/[BurstId])과 표준 타입만 본다 —
 * Spring/JPA 타입을 참조하지 않는다(어댑터가 채운다).
 *
 * **acceptance(T018) — replay 삭제·재구축**: 이 projection 은 event store 의 **파생 읽기 모델**이다. [deleteAll] 로
 * 전부 비운 뒤 event store 를 재생하며 [save] 를 다시 호출하면 동일 projection 이 재구축된다. [save] 는 [BurstId]
 * 유니크 upsert(멱등)라 같은 버스트를 N 번 저장해도 한 행으로 수렴한다.
 */
interface BurstProjectionPort {
    /**
     * [burst] 와 그 fragment 연결을 저장한다(upsert — 같은 [BurstId] 면 상태·revision·fragment 를 갱신).
     * [scrubbedMessageIds] 는 삭제 교정(T015)으로 content 가 비워진 메시지로, combined text 재구성 시 제외 표식이 된다.
     */
    fun save(
        burst: UtteranceBurst,
        revision: Long,
        segmentationVersion: Int,
        scrubbedMessageIds: Set<Long> = emptySet(),
    )

    /** [burstId] projection 을 돌려준다(없으면 null). 재구축 검증·교정 대상 조회용. */
    fun findById(burstId: BurstId): BurstProjectionRecord?

    /** 한 위치·상태의 버스트 projection 목록(재시작 OPEN 버스트 deadline 복구 T019·학습 통계용). */
    fun findByStatus(status: BurstStatus): List<BurstProjectionRecord>

    /** projection 전체 삭제(replay 재구축 전 초기화, acceptance T018). */
    fun deleteAll()
}

/**
 * 버스트 projection 조회 view(원문 미포함, PII medium). 식별자 연결·상태·메타만 — combined text 가 아니라
 * [fragmentMessageIds]/[scrubbedMessageIds] 식별자만 운반한다(원문은 event store 에만, T018).
 */
data class BurstProjectionRecord(
    val burstId: BurstId,
    val guildId: Long,
    val authorId: Long,
    val channelId: Long,
    val threadId: Long?,
    val status: BurstStatus,
    val segmentationVersion: Int,
    val revision: Long,
    val startedAtEpochMs: Long,
    val lastFragmentAtEpochMs: Long,
    /** 버스트가 묶은 메시지 식별자(시간순). */
    val fragmentMessageIds: List<Long>,
    /** 삭제 교정으로 content 가 비워진 메시지 식별자(combined text 재구성 시 제외). */
    val scrubbedMessageIds: Set<Long>,
)
