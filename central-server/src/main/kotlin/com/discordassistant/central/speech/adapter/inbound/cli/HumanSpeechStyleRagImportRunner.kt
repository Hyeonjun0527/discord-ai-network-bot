package com.discordassistant.central.speech.adapter.inbound.cli

import com.discordassistant.central.speech.application.humanstyle.HumanSpeechStyleRagImportService
import com.discordassistant.central.speech.domain.model.HumanSpeechStyleQuality
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.stereotype.Component
import java.nio.file.Path

/**
 * 명시적으로 opt-in한 프로세스에서만 private JSONL을 운영 Speech RAG 테이블로 적재한다.
 *
 * 정상 서버 부팅은 이 runner를 실행하지 않는다. 성공 로그도 개수·반응 방식·embedding 모델만 남겨 카드 내용은 노출하지
 * 않는다.
 */
@Component
@ConditionalOnProperty(
    prefix = "central.nexa.speech-style-rag",
    name = ["import-on-startup"],
    havingValue = "true",
)
class HumanSpeechStyleRagImportRunner(
    private val importer: HumanSpeechStyleRagImportService,
    private val artifactVerifier: HumanSpeechStyleRagImportArtifactVerifier,
    private val applicationContext: ConfigurableApplicationContext,
    @param:Value("\${central.nexa.speech-style-rag.import-artifact-dir:}") private val importArtifactDirectory: String,
    @param:Value("\${central.nexa.speech-style-rag.import-exit-after-completion:false}")
    private val exitAfterCompletion: Boolean = false,
    @param:Value("\${central.nexa.speech-style-rag.import-allowed-quality:CURATION_APPROVED}")
    private val importAllowedQuality: String = HumanSpeechStyleQuality.CURATION_APPROVED.name,
) : ApplicationRunner {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun run(args: ApplicationArguments) {
        require(importArtifactDirectory.isNotBlank()) {
            "NEXA_SPEECH_STYLE_RAG_IMPORT_ARTIFACT_DIR is required when import-on-startup is enabled"
        }
        val allowedQuality =
            HumanSpeechStyleQuality.entries.singleOrNull { it.name == importAllowedQuality }
                ?: throw IllegalArgumentException("NEXA_SPEECH_STYLE_RAG_IMPORT_ALLOWED_QUALITY is unsupported")
        val importFile = artifactVerifier.verify(Path.of(importArtifactDirectory), allowedQuality)
        val report =
            importer.importJsonLines(
                file = importFile,
                allowedQualities = setOf(allowedQuality),
            )
        log.info(
            "Human speech style RAG imported: count={}, promptEligibleCount={}, responseModes={}, embeddingModel={}",
            report.importedCount,
            report.promptEligibleCount,
            report.responseModeCounts,
            report.embeddingModel,
        )
        if (exitAfterCompletion) applicationContext.close()
    }
}
