package com.discordassistant.central.speech.adapter.outbound.globalpromptset

import com.discordassistant.central.globalpromptset.application.GlobalPromptSetService
import com.discordassistant.central.shared.NexaIdentity
import com.discordassistant.central.speech.application.port.out.IdentityKernelBridgePort
import com.discordassistant.central.speech.application.port.out.IdentityKernelMeta
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/**
 * speech [IdentityKernelBridgePort] 의 **읽기 전용** 구현 어댑터(NEXA-P14-T006, ADR 0010 BRIDGE 전략).
 *
 * globalpromptset 의 공개 application API([GlobalPromptSetService.list])로 길드의 승인된 정체성 셋을 **읽기만** 하고
 * [IdentityKernelMeta] 로 명시 매핑한다. 쓰기 경로가 없다(save·update 부재) — 런타임 발화가 정체성을 덮어쓸 side
 * effect 경로가 타입·코드 수준에서 존재하지 않는다(acceptance T006). persona 전문은 가져오지 않는다 — 메타(이름·
 * 관리자 승인 여부)만 노출하고, 본문 조립은 application 의 IdentityKernelAssembler 가 NexaIdentity SSOT 로 한다.
 *
 * 경계(ADR 0010): speech **domain/application** 은 globalpromptset 타입을 import 하지 않는다 — 이 adapter 안에서만
 * 참조한다(socialmemory 의 동명 브리지와 같은 패턴).
 */
@Component
class SpeechIdentityKernelBridge(
    private val promptSets: GlobalPromptSetService,
) : IdentityKernelBridgePort {
    @Transactional(readOnly = true)
    override fun identityMeta(guildId: Long): IdentityKernelMeta {
        val active = promptSets.list(guildId).firstOrNull { it.isDefault }
        val administratorApproved = active != null && !active.builtin
        val personaName = active?.name?.takeIf { it.isNotBlank() } ?: NexaIdentity.NIA_NAME
        // globalpromptset 이 구조화 tag 를 소유하지 않으므로 관심 tag 는 빈 집합으로 노출한다(복제·추론 금지).
        return IdentityKernelMeta.of(
            personaName = personaName,
            interestTags = emptySet(),
            administratorApproved = administratorApproved,
        )
    }
}
