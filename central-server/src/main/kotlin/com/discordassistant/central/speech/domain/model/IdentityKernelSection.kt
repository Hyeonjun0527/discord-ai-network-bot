package com.discordassistant.central.speech.domain.model

/**
 * 니아 정체성의 **짧은 immutable section**(NEXA-P14-T006, 순수 도메인 값 객체·불변).
 *
 * globalpromptset 의 승인된 성격·금지사항·관심사를 발화 프롬프트에 주입할 **정적** 블록으로 표현한다. 정체성
 * SSOT 는 [com.discordassistant.central.shared.NexaIdentity] 이며, 이 section 은 그것을 **읽어 조립한 결과**일
 * 뿐 새 정체성을 만들지 않는다(복제 금지, ADR 0010).
 *
 * **acceptance(T006) — 서버별 정체성과 런타임 사용자 기억이 섞여 저장되지 않는다**: 이 타입에는 사용자 기억
 * 필드가 없다(persona/금지/관심사만). 모든 필드는 val 이고 집합은 [of] 에서 방어 복사돼 불변이라, 런타임 대화가
 * 이 section 으로 정체성을 덮어쓸 경로가 타입 수준에서 존재하지 않는다. 기억은 [MemoryRef] 로 분리 운반된다.
 */
data class IdentityKernelSection(
    /** 적용 중인 정체성 표시 이름(예: 니아). */
    val personaName: String,
    /** 프롬프트에 주입할 정체성 본문(니아 페르소나 SSOT 발췌 — 짧게 조립). */
    val personaBlock: String,
    /** 절대 하지 않을 것(금지사항). 읽기용·불변. */
    val prohibitions: List<String>,
    /** 승인된 관심·정체성 tag. 읽기용·불변. */
    val interests: Set<String>,
) {
    init {
        require(personaName.isNotBlank()) { "personaName 은 비어 있을 수 없다" }
        require(personaBlock.isNotBlank()) { "personaBlock 은 비어 있을 수 없다" }
    }

    companion object {
        /** 집합을 방어 복사해 불변 section 을 만든다(호출자 변형으로 정체성이 바뀌지 않게). */
        fun of(
            personaName: String,
            personaBlock: String,
            prohibitions: Collection<String> = emptyList(),
            interests: Collection<String> = emptySet(),
        ): IdentityKernelSection =
            IdentityKernelSection(
                personaName = personaName,
                personaBlock = personaBlock,
                prohibitions = prohibitions.toList(),
                interests = interests.toSet(),
            )
    }
}
