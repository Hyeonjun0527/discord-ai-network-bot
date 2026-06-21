package com.discordassistant.central.socialmemory.adapter.outbound.globalpromptset

import com.discordassistant.central.globalpromptset.application.GlobalPromptSetService
import com.discordassistant.central.shared.NexaIdentity
import com.discordassistant.central.socialmemory.application.port.out.IdentityKernelBridgePort
import com.discordassistant.central.socialmemory.application.port.out.IdentityKernelSnapshot
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/**
 * [IdentityKernelBridgePort] 의 **읽기 전용** 구현 어댑터(NEXA-P07-T007, ADR 0010 BRIDGE 전략).
 *
 * globalpromptset 의 공개 application API([GlobalPromptSetService.list])로 길드의 승인된 정체성 셋을 **읽기만** 하고
 * [IdentityKernelSnapshot] 으로 명시 매핑한다. 이 어댑터에는 **쓰기 경로가 없다**(save·update 호출 부재) — socialmemory
 * 가 identity kernel 을 덮어쓸 side effect 를 만들 경로가 타입·코드 수준에서 존재하지 않는다(acceptance T007).
 *
 * 경계(ADR 0010): socialmemory **domain** 은 globalpromptset 타입을 import 하지 않는다(NexaArchitectureTest 순수성).
 * globalpromptset 참조는 **이 adapter 안에서만** 일어난다. persona 전문(content)은 가져오지 않는다 — kernel 메타(이름·
 * 관리자 승인 여부)만 노출하고, 기본 미지정 길드는 NEXA 기본 정체성(니아) snapshot 으로 폴백한다.
 */
@Component
class GlobalPromptSetIdentityKernelBridge(
    private val promptSets: GlobalPromptSetService,
) : IdentityKernelBridgePort {
    @Transactional(readOnly = true)
    override fun identityKernel(guildId: Long): IdentityKernelSnapshot {
        // 길드의 기본 지정 셋을 읽기만 한다(content 전문은 가져오지 않음 — kernel 메타만).
        val active = promptSets.list(guildId).firstOrNull { it.isDefault }
        val administratorApproved = active != null && !active.builtin
        val personaName = active?.name?.takeIf { it.isNotBlank() } ?: NexaIdentity.NIA_NAME
        // 정체성 tag 는 globalpromptset 이 아직 구조화 tag 를 소유하지 않으므로 빈 집합으로 노출한다(복제·추론 금지).
        // socialmemory 는 이 snapshot 을 읽기만 하며 tag 를 만들어 저장하지 않는다(ADR 0010).
        return IdentityKernelSnapshot.of(
            personaName = personaName,
            interestTags = emptySet(),
            administratorApproved = administratorApproved,
        )
    }
}
