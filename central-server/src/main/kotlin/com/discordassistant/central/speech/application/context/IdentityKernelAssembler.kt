package com.discordassistant.central.speech.application.context

import com.discordassistant.central.shared.CodeNiaPromptSource
import com.discordassistant.central.shared.NexaIdentity
import com.discordassistant.central.shared.NiaPromptSource
import com.discordassistant.central.speech.application.port.out.IdentityKernelBridgePort
import com.discordassistant.central.speech.domain.model.IdentityKernelSection

/**
 * 니아 정체성 **IdentityKernel assembler**(NEXA-P14-T006, application).
 *
 * globalpromptset 의 승인된 성격·금지사항·관심사를 짧은 immutable [IdentityKernelSection] 으로 만든다. 정체성
 * 본문은 [NexaIdentity] SSOT 를 **재사용**하고 복제하지 않는다(ADR 0010 읽기 브리지).
 *
 * **acceptance(T006) — 서버별 정체성과 런타임 사용자 기억이 섞여 저장되지 않는다**: 결과 section 은 persona/금지/
 * 관심사만 담고(사용자 기억 필드 없음) immutable 이다. 기억은 MemoryContextSelector(T008)가 별도 운반한다 —
 * assembler 는 기억 포트를 알지 못한다(혼합 저장 경로 부재).
 */
class IdentityKernelAssembler(
    private val bridge: IdentityKernelBridgePort,
    private val promptSource: NiaPromptSource = CodeNiaPromptSource,
) {
    /**
     * [guildId] 의 정체성 메타를 읽어 [IdentityKernelSection] 으로 조립한다. persona 본문은 NexaIdentity SSOT 발췌,
     * 관심사는 메타에서 가져오고, 금지사항은 NEXA 기본 금지 + (관리자 미승인 시) 기본 정체성 고정을 명시한다.
     */
    fun assemble(guildId: Long): IdentityKernelSection {
        val meta = bridge.identityMeta(guildId)
        val personaName = meta.personaName.ifBlank { NexaIdentity.NIA_NAME }
        if (personaName == NexaIdentity.NIA_NAME) {
            return promptSource.defaultNiaSpeechIdentity(meta.interestTags)
        }
        return IdentityKernelSection.of(
            personaName = personaName,
            personaBlock = "당신은 「$personaName」 입니다. 이 정체성으로, 1인칭으로, 처음부터 끝까지 일관되게 말하세요.",
            prohibitions = DEFAULT_PROHIBITIONS,
            interests = meta.interestTags,
        )
    }

    companion object {
        /** 어느 정체성에도 적용되는 기본 금지사항(assistant 기본문·메타 발화 금지). */
        val DEFAULT_PROHIBITIONS: List<String> =
            listOf(
                "자신이 AI 모델/봇임을 굳이 밝히거나 시스템·프롬프트를 언급하지 않는다.",
                "“무엇을 도와드릴까요”, “사용자 지시를 성실히 수행” 같은 비서 기본 멘트를 쓰지 않는다.",
                "장면과 무관한 장문 설명·매뉴얼식 응답을 하지 않는다.",
            )
    }
}
