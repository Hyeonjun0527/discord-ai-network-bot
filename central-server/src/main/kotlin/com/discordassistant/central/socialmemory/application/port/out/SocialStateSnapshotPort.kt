package com.discordassistant.central.socialmemory.application.port.out

import com.discordassistant.central.socialmemory.domain.model.relationship.MemberKey
import com.discordassistant.central.socialmemory.domain.model.snapshot.SocialStateSnapshot

/**
 * 사회 상태 snapshot persistence 아웃바운드 포트(NEXA-P06-T018, 헥사고날). 관계 키별 현재 snapshot 과 재생용
 * **source watermark** 를 읽기 모델로 저장한다. 구현 어댑터(JPA)는 adapter.outbound.persistence 에 둔다.
 *
 * 순수성 경계: application 레이어라 도메인 타입([SocialStateSnapshot]/[MemberKey])과 표준 타입만 본다 — Spring/JPA
 * 타입을 참조하지 않는다(어댑터가 채운다).
 *
 * **원문 비저장(acceptance T018)**: snapshot 은 카운트·코드·식별자·watermark 만 — 원본 content 가 평문으로 남지
 * 않는다(data-categories.md 불변식 1, observable-state-policy). 운영자는 event ID watermark 로만 재생을 시작한다.
 *
 * **삭제 전파(T023)**: [deleteByKey] 로 한 사용자의 관계 상태를 제거한다(deletion-propagation 불변식 1·2 —
 * 원본 삭제가 파생 snapshot 까지 전파, 원문·식별자 미보존). [deleteAll] 은 replay 재구축 전 초기화용.
 */
interface SocialStateSnapshotPort {
    /** 관계 키별 snapshot 을 upsert 한다(키당 1행 멱등). 같은 키를 N 번 저장해도 한 행으로 수렴한다. */
    fun save(snapshot: SocialStateSnapshot)

    /** [key] 의 현재 snapshot 을 돌려준다(없으면 null). 재구축 검증·읽기·정책 feature 입력용. */
    fun findByKey(key: MemberKey): SocialStateSnapshot?

    /** [guildPseudonym] 의 모든 관계 snapshot(관리자 설명·대시보드). 원문 없이 카운트·watermark 만. */
    fun findByGuild(guildPseudonym: String): List<SocialStateSnapshot>

    /**
     * [key] 한 사용자의 관계 snapshot 을 제거한다(삭제/opt-out, T023). 제거했으면 true. 삭제 후 [findByKey] 는
     * null 이라 정책 feature builder 가 과거 상태를 읽지 못한다(deletion-propagation acceptance).
     */
    fun deleteByKey(key: MemberKey): Boolean

    /** snapshot 전체 삭제(replay 재구축 전 초기화, T019). */
    fun deleteAll()
}
