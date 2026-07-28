package com.discordassistant.central.shared

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class NexaIdentityTest {
    @Test
    fun `default persona contains one official adult character profile`() {
        assertThat(NexaIdentity.NIA_CHARACTER_PROFILE).contains("20세 성인", "158cm", "46kg")
        assertThat(NexaIdentity.NIA_DEFAULT_PERSONA)
            .containsOnlyOnce(NexaIdentity.NIA_CHARACTER_PROFILE)
            .contains("유튜브·넷플릭스 애니", "밤늦게 몰아보며", "핵심 반전만 숨기고 신나게")
            .doesNotContain("ASCII 마침표", "최근 원문", "출력:", "행위 수행:")
    }

    @Test
    fun `managed nia persona receives the official profile without duplication`() {
        val managedSource =
            NiaPromptSource {
                mapOf(NiaPromptKey.IDENTITY_PERSONA to "운영자가 편집한 니아 정체성")
            }
        val alreadyCompleteSource =
            NiaPromptSource {
                mapOf(
                    NiaPromptKey.IDENTITY_PERSONA to
                        "운영자가 편집한 니아 정체성\n${NexaIdentity.NIA_CHARACTER_PROFILE}",
                )
            }
        val legacyProfileSource =
            NiaPromptSource {
                mapOf(
                    NiaPromptKey.IDENTITY_PERSONA to
                        "운영자가 편집한 니아 정체성\n공식 인물 설정(항상 우선): 20세 성인, 키 158cm, 몸무게 46kg.",
                )
            }

        assertThat(managedSource.niaIdentityPersona())
            .isEqualTo("운영자가 편집한 니아 정체성\n${NexaIdentity.NIA_CHARACTER_PROFILE}")
        assertThat(alreadyCompleteSource.niaIdentityPersona())
            .containsOnlyOnce(NexaIdentity.NIA_CHARACTER_PROFILE)
        assertThat(legacyProfileSource.niaIdentityPersona())
            .containsOnlyOnce(NexaIdentity.NIA_CHARACTER_PROFILE)
            .doesNotContain("몸무게 46kg.\n공식 인물 설정")
    }
}
