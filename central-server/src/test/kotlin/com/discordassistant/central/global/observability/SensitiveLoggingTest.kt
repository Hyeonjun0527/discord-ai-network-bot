package com.discordassistant.central.global.observability

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Files

/** NEXA-P17-T013: 로그 redaction 자동 테스트 — 금지 문자열이 한 건이라도 있으면 탐지(CI 실패). */
class SensitiveLoggingTest {
    @Test
    fun `redactor masks snowflake api key and bearer token`() {
        val raw = "user 123456789012345678 key sk-ABCDEF1234567890XYZ auth Bearer abcdef123456token"
        val redacted = SensitiveLogRedactor.redact(raw)
        assertThat(redacted).doesNotContain("123456789012345678")
        assertThat(redacted).doesNotContain("sk-ABCDEF1234567890XYZ")
        assertThat(redacted).doesNotContain("Bearer abcdef123456token")
        assertThat(redacted).contains("[redacted-id]", "[redacted-key]", "[redacted-token]")
        assertThat(SensitiveLogRedactor.containsSensitive(redacted)).isFalse()
    }

    @Test
    fun `pseudonym labels and allowed fields survive`() {
        val line = "status=200 provider=glm requestId=req_1 user_3 correlationId=corr_9"
        assertThat(SensitiveLogRedactor.redact(line)).isEqualTo(line)
        assertThat(SensitiveLogRedactor.containsSensitive(line)).isFalse()
    }

    @Test
    fun `scanner detects forbidden strings in a leaked log file`() {
        // 대표 원문·snowflake·키를 흘린 로그 파일을 만들고 스캐너가 탐지하는지(비-0 종료) 검증.
        val log = Files.createTempFile("nexa-leak", ".log")
        Files.write(
            log,
            listOf(
                "ok status=200 requestId=req_1",
                "leak userId 123456789012345678",
                "leak key AIzaSyABCDEF1234567890_test-key",
            ),
        )
        val script = repoRoot().resolve("scripts/scan-sensitive-logs.py").toString()
        val exit =
            ProcessBuilder("python3", script, log.toString())
                .redirectErrorStream(true)
                .start()
                .waitFor()
        // 금지 문자열이 있으면 스캐너가 비-0 으로 실패한다(acceptance — CI 실패).
        assertThat(exit).isNotEqualTo(0)
        Files.deleteIfExists(log)
    }

    @Test
    fun `scanner passes on a clean redacted log file`() {
        val log = Files.createTempFile("nexa-clean", ".log")
        Files.write(
            log,
            listOf("status=200 user_3 [redacted-id] [redacted-key]"),
        )
        val script = repoRoot().resolve("scripts/scan-sensitive-logs.py").toString()
        val exit =
            ProcessBuilder("python3", script, log.toString())
                .redirectErrorStream(true)
                .start()
                .waitFor()
        assertThat(exit).isEqualTo(0)
        Files.deleteIfExists(log)
    }

    /** central-server 모듈 작업 디렉터리에서 저장소 루트로 올라간다. */
    private fun repoRoot(): java.nio.file.Path {
        var dir =
            java.nio.file.Paths
                .get("")
                .toAbsolutePath()
        while (!Files.exists(dir.resolve("scripts/scan-sensitive-logs.py")) && dir.parent != null) {
            dir = dir.parent
        }
        return dir
    }
}
