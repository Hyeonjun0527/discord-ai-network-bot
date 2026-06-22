package com.discordassistant.central.speech.application.port.out

/**
 * speech 가 길드의 **승인된 정체성 메타를 읽기만** 하는 아웃바운드 포트(NEXA-P14-T006, ADR 0010 BRIDGE 전략).
 *
 * IdentityKernel(정적 페르소나·관심 tag)은 globalpromptset 소유다 — speech 는 발화 프롬프트 조립을 위해 이를
 * **읽기만** 하고 복제·변경하지 않는다. 이 포트에는 쓰기 메서드가 없다(read-only) — 런타임 발화가 정체성을 자동
 * 덮어쓸 경로가 타입 수준에서 존재하지 않는다(acceptance T006). 구현 adapter 안에서만 globalpromptset 을 참조하고,
 * speech application/domain 은 globalpromptset 타입을 import 하지 않는다(NexaArchitectureTest 순수성).
 */
interface IdentityKernelBridgePort {
    /**
     * [guildId] 의 승인된 정체성 메타를 immutable snapshot 으로 조회한다. 기본 persona 미지정 길드는 NEXA 기본
     * 정체성(니아) snapshot 을 돌려준다.
     */
    fun identityMeta(guildId: Long): IdentityKernelMeta
}

/**
 * IdentityKernel 의 읽기 전용 메타(NEXA-P14-T006). persona 표시 이름·승인 관심 tag·관리자 승인 여부만 노출한다 —
 * persona 본문(SSOT)은 speech application 의 IdentityKernelAssembler 가 NexaIdentity 에서 조립한다(복제 금지).
 */
data class IdentityKernelMeta(
    val personaName: String,
    val interestTags: Set<String>,
    val administratorApproved: Boolean,
) {
    init {
        require(personaName.isNotBlank()) { "personaName 은 비어 있을 수 없다" }
    }

    companion object {
        fun of(
            personaName: String,
            interestTags: Collection<String>,
            administratorApproved: Boolean,
        ): IdentityKernelMeta = IdentityKernelMeta(personaName, interestTags.toSet(), administratorApproved)
    }
}
