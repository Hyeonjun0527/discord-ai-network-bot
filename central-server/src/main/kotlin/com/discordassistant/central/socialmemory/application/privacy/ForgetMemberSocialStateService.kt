package com.discordassistant.central.socialmemory.application.privacy

import com.discordassistant.central.socialmemory.application.port.out.SocialStateSnapshotPort
import com.discordassistant.central.socialmemory.domain.model.relationship.MemberKey
import org.springframework.stereotype.Service

/**
 * 사용자 삭제·reset 유스케이스(NEXA-P06-T023, deletion-propagation 준수).
 *
 * 옵트아웃/삭제 요청 시 그 사용자의 관계 snapshot 과 source link(watermark·결과 카운트)를 **제거**한다 — 원문·식별자
 * 원문을 보존하지 않는다(deletion-propagation 불변식 1·2: 원본→파생 전파, 미보존). 동의 철회도 같은 제거 경로를 쓴다
 * (불변식 3: 신규 수집 중단은 ingest 경로의 책임, 여기선 기존 제거를 담당).
 *
 * **acceptance(T023) — 삭제 후 정책 feature builder 가 해당 사용자의 과거 상태를 읽지 못한다**: [forget] 후
 * [SocialStateSnapshotPort.findByKey] 는 null 이라([wasPresent] 로 실제 제거 여부 보고), 어떤 정책 feature builder 도
 * 과거 관계 상태를 읽을 경로가 없다.
 *
 * 순수 application: 도메인 타입·아웃바운드 포트만 본다 — JPA/JDA 타입 미참조. ainetwork 호감도는 건드리지 않는다
 * (ADR 0010 — socialmemory 는 호감도를 소유·삭제하지 않는다; 그 삭제는 ainetwork 책임).
 */
@Service
class ForgetMemberSocialStateService(
    private val snapshots: SocialStateSnapshotPort,
) {
    /**
     * [key] 사용자의 관계 snapshot 과 source link 를 제거한다(삭제/opt-out/동의 철회). 자식 결과 카운트도 함께
     * 사라진다(persistence CASCADE). 실제로 제거된 행이 있었으면 true.
     */
    fun forget(key: MemberKey): ForgetResult {
        val removed = snapshots.deleteByKey(key)
        // 삭제 검증: 제거 후에는 어떤 과거 상태도 조회되지 않아야 한다(acceptance T023).
        val stillReadable = snapshots.findByKey(key) != null
        check(!stillReadable) { "삭제 후에도 관계 상태가 조회됨 — deletion-propagation 위반" }
        return ForgetResult(wasPresent = removed)
    }

    /**
     * reset — 삭제와 동일하게 기존 관계 상태를 제거한다. socialmemory snapshot 은 event store 의 파생이라 reset 은
     * "현재 파생 상태를 비움"이며, 필요 시 운영자가 event store 재생([RebuildSocialStateService])으로 재구축할 수 있다
     * (단, 사용자 삭제 요청 reset 은 재구축하지 않는다 — 원본 이벤트까지 제거되는 경우 재생해도 빈 상태).
     */
    fun reset(key: MemberKey): ForgetResult = forget(key)
}

/** 삭제·reset 결과(원문 미포함). 실제로 제거된 상태가 있었는지([wasPresent])만 보고한다. */
data class ForgetResult(
    val wasPresent: Boolean,
)
