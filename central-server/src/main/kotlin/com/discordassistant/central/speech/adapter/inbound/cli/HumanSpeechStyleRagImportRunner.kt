package com.discordassistant.central.speech.adapter.inbound.cli

import com.discordassistant.central.speech.application.humanstyle.HumanSpeechStyleRagImportService
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
    private val applicationContext: ConfigurableApplicationContext,
    @param:Value("\${central.nexa.speech-style-rag.import-file:}") private val importFile: String,
    @param:Value("\${central.nexa.speech-style-rag.import-exit-after-completion:false}")
    private val exitAfterCompletion: Boolean = false,
) : ApplicationRunner {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun run(args: ApplicationArguments) {
        require(importFile.isNotBlank()) { "NEXA_SPEECH_STYLE_RAG_IMPORT_FILE is required when import-on-startup is enabled" }
        val report = importer.importJsonLines(Path.of(importFile))
        log.info(
            "Human speech style RAG imported: count={}, responseModes={}, embeddingModel={}",
            report.importedCount,
            report.responseModeCounts,
            report.embeddingModel,
        )
        if (exitAfterCompletion) applicationContext.close()
    }
}
