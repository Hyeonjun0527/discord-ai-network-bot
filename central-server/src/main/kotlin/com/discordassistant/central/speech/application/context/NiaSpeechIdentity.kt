package com.discordassistant.central.speech.application.context

import com.discordassistant.central.shared.NexaIdentity
import com.discordassistant.central.shared.NiaPromptKey
import com.discordassistant.central.shared.NiaPromptSource
import com.discordassistant.central.shared.niaIdentityPersona
import com.discordassistant.central.speech.domain.model.IdentityKernelSection

/** 운영·데모·관리 경로가 같은 니아 Speech 정체성 문서를 조립하도록 유지한다. */
fun NiaPromptSource.defaultNiaSpeechIdentity(interests: Collection<String> = emptySet()): IdentityKernelSection {
    val persona =
        listOf(
            niaIdentityPersona(),
            text(NiaPromptKey.SPEECH_PERSONA_RULES),
        ).joinToString("\n\n")
    return IdentityKernelSection.of(
        personaName = NexaIdentity.NIA_NAME,
        personaBlock = persona,
        prohibitions =
            text(NiaPromptKey.IDENTITY_PROHIBITIONS)
                .lineSequence()
                .map(String::trim)
                .filter(String::isNotBlank)
                .toList(),
        interests = interests,
    )
}
