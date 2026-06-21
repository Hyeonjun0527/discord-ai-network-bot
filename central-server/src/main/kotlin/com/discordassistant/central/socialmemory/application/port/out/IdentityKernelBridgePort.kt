package com.discordassistant.central.socialmemory.application.port.out

/**
 * socialmemory 가 **IdentityKernel(정적 정체성)을 읽기만** 하는 아웃바운드 포트(NEXA-P07-T007, ADR 0010 BRIDGE 전략).
 *
 * IdentityKernel(니아 정체성·승인된 관심 tag)은 ainetwork/globalpromptset 소유의 **정적** 페르소나다 — 기억이 아니다
 * (taxonomy.md 불변식 2). socialmemory 는 speech 맥락 구성을 위해 이를 참조하되 **복제·변경하지 않는다**. 이 포트에는
 * 쓰기 메서드가 없다(read-only) — 런타임 대화가 identity kernel 을 자동 덮어쓸 경로가 타입 수준에서 존재하지 않는다
 * (acceptance T007).
 */
interface IdentityKernelBridgePort {
    /**
     * [guildId] 의 승인된 정체성·관심 tag 를 **immutable snapshot** 으로 조회한다. 기본 persona 가 지정되지 않은 길드는
     * NEXA 기본 정체성(니아) snapshot 을 돌려준다. 반환 [IdentityKernelSnapshot] 은 불변이라 호출자가 변형할 수 없다.
     */
    fun identityKernel(guildId: Long): IdentityKernelSnapshot
}

/**
 * IdentityKernel 의 **읽기 전용 immutable snapshot**(NEXA-P07-T007). globalpromptset 의 승인된 정체성 페르소나와 관심
 * tag 를 그대로 노출하되, socialmemory 는 이를 저장·변경하지 않는다(복제 금지, ADR 0010). 모든 필드는 val 이고 tag 집합은
 * 생성 시 방어적으로 불변 복사된다([of]) — 런타임 대화가 이 snapshot 으로 정체성을 덮어쓸 수 없다(acceptance T007).
 */
data class IdentityKernelSnapshot(
    /** 길드에 적용 중인 정체성 persona 의 표시 이름(예: 니아). */
    val personaName: String,
    /** 승인된 관심·정체성 tag(읽기용·불변). */
    val interestTags: Set<String>,
    /** 이 정체성이 길드 관리자가 지정한 사용자 셋인가(false 면 기본 니아 정체성). */
    val administratorApproved: Boolean,
) {
    init {
        require(personaName.isNotBlank()) { "personaName 은 비어 있을 수 없다" }
    }

    companion object {
        /** tag 컬렉션을 방어 복사해 불변 snapshot 을 만든다(호출자 변형으로 kernel 이 바뀌지 않게). */
        fun of(
            personaName: String,
            interestTags: Collection<String>,
            administratorApproved: Boolean,
        ): IdentityKernelSnapshot = IdentityKernelSnapshot(personaName, interestTags.toSet(), administratorApproved)
    }
}
