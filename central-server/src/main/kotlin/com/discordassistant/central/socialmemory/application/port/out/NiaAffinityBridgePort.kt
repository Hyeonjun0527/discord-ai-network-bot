package com.discordassistant.central.socialmemory.application.port.out

/**
 * ainetwork 호감도 **읽기 브리지** 아웃바운드 포트(NEXA-P06-T020, ADR 0010 BRIDGE 전략).
 *
 * socialmemory 는 ainetwork 의 `user_affinity`(니아 identity·기존 호감도)를 **읽기만** 한다 — score/stage 를
 * 복제 저장하지 않고, 명시적으로 매핑한 [NiaAffinityView] 만 선택적 입력으로 본다(ADR 0010 불변식: 직접 import·
 * 이중 쓰기 금지, ArchUnit 으로 엔티티 직접 import 차단).
 *
 * 구현 어댑터(adapter.outbound.ainetwork)가 ainetwork 읽기 리포지토리를 호출해 매핑한다. 이 포트는 **쓰기 메서드가
 * 없다** — 타입 수준에서 socialmemory 가 호감도를 갱신할 경로가 존재하지 않는다(acceptance T021 중복 쓰기 방지).
 */
interface NiaAffinityBridgePort {
    /**
     * [userId] 의 ainetwork 호감도를 매핑한 읽기 뷰를 돌려준다(없으면 null). score/stage 원본이 아니라 정규화·매핑된
     * 표현만 노출한다([NiaAffinityView]). 부수효과 없음(읽기 전용).
     */
    fun affinityView(userId: Long): NiaAffinityView?
}

/**
 * ainetwork 호감도를 socialmemory 가 소비할 수 있게 **매핑한 읽기 뷰**(NEXA-P06-T020). ainetwork 의 score(전역
 * 게임화 카운터)·내부 stage 라벨을 그대로 복제하지 않고, **단계 서수**와 **정규화 친밀도**만 노출한다 — socialmemory 의
 * guild-scoped 관찰 관계와 명확히 구분되는 *전역* 진척 신호임을 타입으로 드러낸다(ADR 0010).
 *
 * 순수 application value type: ainetwork 엔티티를 참조하지 않는다(어댑터가 채운다).
 */
data class NiaAffinityView(
    /** 전역 호감도 단계 서수(0=낯섦에서 단조 증가). 관계 *감정* 단정이 아니라 전역 진척 단계의 서수다. */
    val stageOrdinal: Int,
    /**
     * 전역 진척을 [0,1] 로 정규화한 친밀도 신호. socialmemory 의 guild-scoped familiarity 와 *다른* 출처임을 명확히
     * 한다 — speech 가 둘을 하나로 조립할 때 이중 주입을 피하는 입력(ADR 0010).
     */
    val normalizedAffinity: Double,
) {
    init {
        require(stageOrdinal >= 0) { "stageOrdinal 은 음수일 수 없다" }
        require(normalizedAffinity in 0.0..1.0) { "normalizedAffinity 는 [0,1] 범위여야 한다" }
    }
}
