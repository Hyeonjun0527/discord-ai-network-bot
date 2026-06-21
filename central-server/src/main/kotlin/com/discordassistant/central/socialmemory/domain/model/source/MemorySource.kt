package com.discordassistant.central.socialmemory.domain.model.source

import java.time.Instant

/**
 * 한 기억 항목의 **출처(provenance)** — NEXA 가 그 기억을 무엇에서·어떻게·어떤 동의 상태에서 만들었는지
 * (NEXA-P07-T002, 순수 도메인 값 객체·불변).
 *
 * 원문을 복제하지 않는다(data-categories.md: 메시지 원문은 High 등급·비영속). 대신 원천 이벤트 ID([sourceEventIds])
 * 만 운반한다 — 삭제 전파(deletion-propagation) 시 이 ID 로 어떤 기억이 무효화돼야 하는지 역추적한다(T013). 추출 규칙
 * 버전([extractionVersion])은 재추출·재투영 비교 기준이고, 동의 스냅샷([consentGranted])은 그 기억이 만들어진 시점의
 * 옵트인 여부다(동의 철회 시 재계산·제거의 근거, deletion-propagation 불변식 3).
 *
 * **acceptance(T002) — 출처 없는 기억 저장을 생성자 수준에서 거부한다**: [sourceEventIds] 가 비면 [init] 가
 * `require` 로 거부한다. 따라서 provenance 없는 [MemorySource] 인스턴스 자체가 만들어질 수 없고, 모든 기억 aggregate
 * 가 이 타입을 필수 필드로 갖게 하면 출처 없는 기억이 도메인에 존재할 수 없다.
 *
 * 순수성: Spring/JPA/JDA·ainetwork 엔티티 미참조. 표준 java.time 만 쓴다.
 */
data class MemorySource(
    /**
     * 이 기억을 뒷받침하는 원천 이벤트(burst/scene 등) ID 집합. 원문이 아니라 ID 만 — 최소 1개 필수(출처 없는
     * 기억 거부). 삭제 전파(T013) 시 이 ID redaction 이 기억 무효화·confidence 재계산을 트리거한다.
     */
    val sourceEventIds: Set<String>,
    /** 이 기억을 만든 추출 규칙 버전. 재추출·재투영 비교 기준. */
    val extractionVersion: Long,
    /** 기억 생성 시점의 옵트인(동의) 스냅샷. 동의 철회 시 제거·재계산 근거(deletion-propagation 불변식 3). */
    val consentGranted: Boolean,
    /** 이 출처 메타가 기록된 시각(Clock 기반 주입). */
    val createdAt: Instant,
) {
    init {
        require(sourceEventIds.isNotEmpty()) { "출처 없는 기억은 저장할 수 없다 — sourceEventIds 는 최소 1개여야 한다" }
        require(sourceEventIds.none { it.isBlank() }) { "sourceEventIds 의 원소는 비어 있을 수 없다" }
        require(extractionVersion >= 0) { "extractionVersion 은 음수일 수 없다" }
    }

    /** 이 기억을 뒷받침하는 서로 다른 원천 이벤트 수. confidence 반복 언급 가중·재계산(T010/T013) 의 입력. */
    val supportCount: Int
        get() = sourceEventIds.size

    /**
     * 일부 원천 이벤트([redactedEventIds])가 삭제됐을 때 남은 출처. 모두 사라지면 null(기억 전체 무효화 신호, T013).
     * 원문을 보존하지 않으므로 ID 차집합만으로 부분 출처 잔존을 판정한다(deletion-propagation 불변식 1·2).
     */
    fun withoutEvents(redactedEventIds: Set<String>): MemorySource? {
        val remaining = sourceEventIds - redactedEventIds
        if (remaining.isEmpty()) return null
        return copy(sourceEventIds = remaining)
    }

    /** [eventId] 가 이 기억의 출처 중 하나인가(삭제 전파 영향 판정). */
    fun isSupportedBy(eventId: String): Boolean = eventId in sourceEventIds
}
