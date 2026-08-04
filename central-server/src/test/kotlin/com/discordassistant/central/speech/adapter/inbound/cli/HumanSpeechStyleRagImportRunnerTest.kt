package com.discordassistant.central.speech.adapter.inbound.cli

import com.discordassistant.central.speech.application.humanstyle.HumanSpeechStyleImportReport
import com.discordassistant.central.speech.application.humanstyle.HumanSpeechStyleRagImportService
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
    private val applicationContext = mock(ConfigurableApplicationContext::class.java)

    @Test
    fun `one-shot import closes its own application context after a successful import`() {
        val file = Path.of("/private/human-speech-style-cards.jsonl")
        `when`(importer.importJsonLines(file)).thenReturn(HumanSpeechStyleImportReport(1, emptyMap(), "text-embedding-3-small"))

        HumanSpeechStyleRagImportRunner(importer, applicationContext, file.toString(), exitAfterCompletion = true)
            .run(DefaultApplicationArguments())

        verify(applicationContext).close()
    }

    @Test
    fun `ordinary import leaves the application running`() {
        val file = Path.of("/private/human-speech-style-cards.jsonl")
        `when`(importer.importJsonLines(file)).thenReturn(HumanSpeechStyleImportReport(1, emptyMap(), "text-embedding-3-small"))

        HumanSpeechStyleRagImportRunner(importer, applicationContext, file.toString())
            .run(DefaultApplicationArguments())

        verifyNoInteractions(applicationContext)
    }
}
