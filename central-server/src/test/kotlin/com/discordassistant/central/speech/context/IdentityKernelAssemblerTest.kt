package com.discordassistant.central.speech.context

import com.discordassistant.central.shared.CodeNiaPromptSource
import com.discordassistant.central.shared.NexaIdentity
import com.discordassistant.central.shared.NiaPromptKey
import com.discordassistant.central.shared.niaIdentityPersona
import com.discordassistant.central.speech.application.context.IdentityKernelAssembler
import com.discordassistant.central.speech.application.port.out.IdentityKernelBridgePort
import com.discordassistant.central.speech.application.port.out.IdentityKernelMeta
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** NEXA-P14-T006: IdentityKernel assembler — NexaIdentity SSOT 재사용(복제 금지)·기억 미혼합. */
class IdentityKernelAssemblerTest {
    private fun assembler(meta: IdentityKernelMeta) =
        IdentityKernelAssembler(
            object : IdentityKernelBridgePort {
                override fun identityMeta(guildId: Long) = meta
            },
        )

    @Test
    fun `default nia speech persona is assembled from the administrator prompt source`() {
        val section =
            assembler(IdentityKernelMeta.of("니아", emptySet(), administratorApproved = false)).assemble(1L)
        assertThat(section.personaBlock).isEqualTo(
            CodeNiaPromptSource.niaIdentityPersona() + "\n\n" +
                CodeNiaPromptSource.text(NiaPromptKey.SPEECH_PERSONA_RULES),
        )
        assertThat(section.personaBlock).contains(
            "Discord 대화방",
            "사람을 좋아하고 친절하며",
            "확실히 모르는 사실",
            "실제 사람처럼 대화하세요.",
        )
        assertThat(section.personaBlock).contains(NexaIdentity.NIA_CHARACTER_PROFILE)
        assertThat(section.personaBlock).doesNotContain(
            "이번 응답에서 실제로 끝낸다",
            "반복이 누적되면",
            "ASCII 마침표",
            "ㅋㅋ",
        )
        assertThat(section.personaName).isEqualTo(NexaIdentity.NIA_NAME)
        assertThat(section.interests).isEmpty()
    }

    @Test
    fun `default nia persona keeps only identity and capability boundaries`() {
        val prompt =
            NexaIdentity.NIA_DEFAULT_PERSONA + "\n" + NexaIdentity.NIA_FEWSHOT + "\n" +
                CodeNiaPromptSource.text(NiaPromptKey.IDENTITY_PROHIBITIONS)

        assertThat(prompt).contains(
            "Discord 대화방",
            "실제 사람처럼 대화하세요.",
            "게임·전화·직접 만나기·물건 사주기",
        )
        assertThat(prompt).doesNotContain(
            "사용자: 안녕?",
            "니아: 안녕하세요",
            "안녕하세요, 저는 니아예요",
            "오늘은 어떤 걸 도와드릴까요",
            "제가 길을 찾아볼게요",
            "충실하게 답하세요",
            "친절하게",
            "자연스럽게 말한다",
            "마침표",
        )
    }

    @Test
    fun `guild-specified persona does not clone nia full persona`() {
        val section =
            assembler(IdentityKernelMeta.of("아리", setOf("음악"), administratorApproved = true)).assemble(7L)
        assertThat(section.personaName).isEqualTo("아리")
        assertThat(section.personaBlock).contains("아리")
        assertThat(section.personaBlock).isNotEqualTo(CodeNiaPromptSource.niaIdentityPersona())
        assertThat(section.personaBlock).doesNotContain(NexaIdentity.NIA_CHARACTER_PROFILE)
        assertThat(section.interests).containsExactly("음악")
    }

    @Test
    fun `section carries no user-memory field (identity and memory not mixed)`() {
        val section =
            assembler(IdentityKernelMeta.of("니아", emptySet(), administratorApproved = false)).assemble(1L)
        // IdentityKernelSection 은 persona/금지/관심만 — 기억 필드가 타입에 없다(컴파일 보증). 금지사항은 채워진다.
        assertThat(section.prohibitions).isNotEmpty()
        assertThat(section.prohibitions).anyMatch { it.contains("다른 인물인 척하지 않는다") }
    }
}
