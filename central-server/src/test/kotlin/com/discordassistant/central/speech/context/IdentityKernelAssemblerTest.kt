package com.discordassistant.central.speech.context

import com.discordassistant.central.shared.NexaIdentity
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
    fun `default nia speech persona reuses NexaIdentity SSOT verbatim (no duplication)`() {
        val section =
            assembler(IdentityKernelMeta.of("니아", emptySet(), administratorApproved = false)).assemble(1L)
        // SSOT 본문을 그대로 읽어 넣는다 — assembler 가 다시 정의하지 않는다.
        assertThat(section.personaBlock).isEqualTo(NexaIdentity.NIA_SPEECH_PERSONA)
        assertThat(section.personaBlock).contains("다익스트라 알고리즘 알려줘")
        assertThat(section.personaBlock).contains("니아 기능채널 ai채팅에다가 물어봐")
        assertThat(section.personaBlock).doesNotContain("왜 자꾸 불러 ㅋㅋ", "할 말 있으면 해봐")
        assertThat(section.personaName).isEqualTo(NexaIdentity.NIA_NAME)
    }

    @Test
    fun `default nia persona follows core traits without assistant greeting boilerplate`() {
        val prompt = NexaIdentity.NIA_DEFAULT_PERSONA + "\n" + NexaIdentity.NIA_FEWSHOT

        assertThat(prompt).contains("친구 단톡방", "친근함", "장난스러움", "솔직함")
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
        assertThat(section.personaBlock).isNotEqualTo(NexaIdentity.NIA_SPEECH_PERSONA)
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
