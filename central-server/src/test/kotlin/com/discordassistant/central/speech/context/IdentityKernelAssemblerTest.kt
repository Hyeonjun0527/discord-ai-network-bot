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
        assertThat(section.personaBlock).contains("이번 응답에서 실제로 끝낸다")
        assertThat(section.personaBlock).contains("예고하지 말고")
        assertThat(section.personaBlock).contains("친구 단톡방의 한 사람", "확실히 모르는 사실")
        assertThat(section.personaBlock).contains("자연스러운 한국어 구어체")
        assertThat(section.personaBlock).contains(NexaIdentity.NIA_CHARACTER_PROFILE)
        assertThat(section.personaBlock).doesNotContain("와이파이 비번", "커피 먼저 주문하셔야 돼요", "그만 좀 해라 ㅋㅋ")
        assertThat(section.personaName).isEqualTo(NexaIdentity.NIA_NAME)
        assertThat(section.interests).isEmpty()
    }

    @Test
    fun `default nia persona follows core traits without assistant greeting boilerplate`() {
        val prompt = NexaIdentity.NIA_DEFAULT_PERSONA + "\n" + NexaIdentity.NIA_FEWSHOT

        assertThat(prompt).contains("친구 단톡방", "친근하고", "장난스럽고", "솔직하다")
        assertThat(prompt).doesNotContain(
            "사용자: 안녕?",
            "니아: 안녕하세요",
            "안녕하세요, 저는 니아예요",
            "오늘은 어떤 걸 도와드릴까요",
            "제가 길을 찾아볼게요",
            "충실하게 답하세요",
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
        assertThat(section.prohibitions).anyMatch { it.contains("비서") || it.contains("AI") }
    }
}
