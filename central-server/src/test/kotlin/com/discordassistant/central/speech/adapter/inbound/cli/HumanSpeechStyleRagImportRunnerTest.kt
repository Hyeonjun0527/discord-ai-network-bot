package com.discordassistant.central.speech.adapter.inbound.cli

import com.discordassistant.central.speech.application.humanstyle.HumanSpeechStyleImportReport
import com.discordassistant.central.speech.application.humanstyle.HumanSpeechStyleRagImportService
import com.discordassistant.central.speech.domain.model.HumanSpeechStyleQuality
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import org.springframework.boot.DefaultApplicationArguments
import org.springframework.context.ConfigurableApplicationContext
import java.nio.file.Path

class HumanSpeechStyleRagImportRunnerTest {
    private val importer = mock(HumanSpeechStyleRagImportService::class.java)
    private val artifactVerifier = mock(HumanSpeechStyleRagImportArtifactVerifier::class.java)
    private val applicationContext = mock(ConfigurableApplicationContext::class.java)

    @Test
    fun `one-shot import closes its own application context after a successful import`() {
        val artifactDirectory = "/private/human-speech-style-rag-import"
        val file = Path.of("$artifactDirectory/human-speech-style-cards.jsonl")
        `when`(artifactVerifier.verify(Path.of(artifactDirectory), HumanSpeechStyleQuality.CURATION_APPROVED))
            .thenReturn(file)
        `when`(importer.importJsonLines(file, formalQualityOnly))
            .thenReturn(HumanSpeechStyleImportReport(1, 1, emptyMap(), "text-embedding-3-small"))

        HumanSpeechStyleRagImportRunner(importer, artifactVerifier, applicationContext, artifactDirectory, exitAfterCompletion = true)
            .run(DefaultApplicationArguments())

        verify(artifactVerifier).verify(Path.of(artifactDirectory), HumanSpeechStyleQuality.CURATION_APPROVED)
        verify(importer).importJsonLines(file, formalQualityOnly)
        verify(applicationContext).close()
    }

    @Test
    fun `ordinary import leaves the application running`() {
        val artifactDirectory = "/private/human-speech-style-rag-import"
        val file = Path.of("$artifactDirectory/human-speech-style-cards.jsonl")
        `when`(artifactVerifier.verify(Path.of(artifactDirectory), HumanSpeechStyleQuality.CURATION_APPROVED))
            .thenReturn(file)
        `when`(importer.importJsonLines(file, formalQualityOnly))
            .thenReturn(HumanSpeechStyleImportReport(1, 1, emptyMap(), "text-embedding-3-small"))

        HumanSpeechStyleRagImportRunner(importer, artifactVerifier, applicationContext, artifactDirectory)
            .run(DefaultApplicationArguments())

        verify(artifactVerifier).verify(Path.of(artifactDirectory), HumanSpeechStyleQuality.CURATION_APPROVED)
        verify(importer).importJsonLines(file, formalQualityOnly)
        verifyNoInteractions(applicationContext)
    }

    @Test
    fun `명시한 user released quality로 one-shot import를 제한할 수 있다`() {
        val artifactDirectory = "/private/human-speech-style-rag-import"
        val file = Path.of("$artifactDirectory/human-speech-style-cards.jsonl")
        val userReleasedOnly = setOf(HumanSpeechStyleQuality.USER_RELEASED_REVIEW)
        `when`(artifactVerifier.verify(Path.of(artifactDirectory), HumanSpeechStyleQuality.USER_RELEASED_REVIEW))
            .thenReturn(file)
        `when`(importer.importJsonLines(file, userReleasedOnly))
            .thenReturn(HumanSpeechStyleImportReport(1, 1, emptyMap(), "text-embedding-3-small"))

        HumanSpeechStyleRagImportRunner(
            importer,
            artifactVerifier,
            applicationContext,
            artifactDirectory,
            importAllowedQuality = HumanSpeechStyleQuality.USER_RELEASED_REVIEW.name,
        ).run(DefaultApplicationArguments())

        verify(artifactVerifier).verify(Path.of(artifactDirectory), HumanSpeechStyleQuality.USER_RELEASED_REVIEW)
        verify(importer).importJsonLines(file, userReleasedOnly)
        verifyNoInteractions(applicationContext)
    }

    @Test
    fun `지원하지 않는 import quality는 카드 적재 전에 거절한다`() {
        assertThatThrownBy {
            HumanSpeechStyleRagImportRunner(
                importer,
                artifactVerifier,
                applicationContext,
                "/private/human-speech-style-rag-import",
                importAllowedQuality = "UNSUPPORTED",
            ).run(DefaultApplicationArguments())
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("NEXA_SPEECH_STYLE_RAG_IMPORT_ALLOWED_QUALITY is unsupported")

        verifyNoInteractions(importer, applicationContext)
    }

    private companion object {
        val formalQualityOnly = setOf(HumanSpeechStyleQuality.CURATION_APPROVED)
    }
}
