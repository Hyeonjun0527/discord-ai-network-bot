package com.discordassistant.central.niaprompt

import com.discordassistant.central.niaprompt.adapter.outbound.persistence.NiaPromptConfigurationEntity
import com.discordassistant.central.niaprompt.adapter.outbound.persistence.NiaPromptConfigurationRepository
import com.discordassistant.central.niaprompt.application.NiaPromptConfigurationService
import com.discordassistant.central.platform.discord.nexa.NexaBuiltInFewShotCatalogAdapter
import com.discordassistant.central.shared.NiaPromptDefaults
import com.discordassistant.central.shared.NiaPromptKey
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Optional

class NiaPromptConfigurationServiceTest {
    @Test
    fun `배포 기본값은 관리 화면에 그대로 보이고 판단 예시 제목은 한국어다`() {
        val service = serviceWithMemoryRepository()

        val view = service.load()

        assertThat(view.source).isEqualTo("CODE_DEFAULT")
        assertThat(view.activeDocuments).containsAllEntriesOf(NiaPromptDefaults.documents)
        assertThat(NexaBuiltInFewShotCatalogAdapter().catalog().judgeExamples)
            .allSatisfy { assertThat(it.title).containsPattern("[가-힣]") }
    }

    @Test
    fun `관리자가 저장한 전체 문서는 적용 전에는 런타임을 바꾸지 않고 적용 후 원자적으로 바꾼다`() {
        val service = serviceWithMemoryRepository()
        val edited = NiaPromptDefaults.documents.mapKeys { it.key.wireName }.toMutableMap()
        edited[NiaPromptKey.IDENTITY_PERSONA.wireName] = "관리자가 수정한 니아 정체성"

        val draft = service.saveDraft(edited, 42L)
        assertThat(draft.dirty).isTrue()
        assertThat(service.text(NiaPromptKey.IDENTITY_PERSONA)).isEqualTo(NiaPromptDefaults.text(NiaPromptKey.IDENTITY_PERSONA))

        val applied = service.applyDraft(42L)
        assertThat(applied.activeVersion).isEqualTo(1)
        assertThat(applied.dirty).isFalse()
        assertThat(service.text(NiaPromptKey.IDENTITY_PERSONA)).isEqualTo("관리자가 수정한 니아 정체성")
    }

    private fun serviceWithMemoryRepository(): NiaPromptConfigurationService {
        val repository = mock(NiaPromptConfigurationRepository::class.java)
        var stored: NiaPromptConfigurationEntity? = null
        `when`(repository.findById(NiaPromptConfigurationEntity.SINGLETON_ID)).thenAnswer { Optional.ofNullable(stored) }
        doAnswer { invocation ->
            invocation.getArgument<NiaPromptConfigurationEntity>(0).also { stored = it }
        }.`when`(repository).save(any(NiaPromptConfigurationEntity::class.java))
        return NiaPromptConfigurationService(
            repository,
            jacksonObjectMapper(),
            Clock.fixed(Instant.parse("2026-07-22T00:00:00Z"), ZoneOffset.UTC),
        )
    }
}
