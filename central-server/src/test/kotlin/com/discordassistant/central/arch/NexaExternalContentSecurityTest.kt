package com.discordassistant.central.arch

import com.discordassistant.central.knowledge.application.KnowledgeSourceValidator
import com.discordassistant.central.knowledge.domain.model.KnowledgeSourceStatus
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * NEXA-P17-T022 — SSRF·URL attachment 경계 테스트.
 *
 * 메시지 URL/attachment 를 **speech 발화 생성이나 사회 경로가 자동 fetch 하지 않는다**. 외부 네트워크 접근은
 * 오직 **허용된 retrieval adapter**(knowledge 의 검증·fetch 경계)만 수행하고, 그조차 SSRF/내부망/메타데이터
 * 주소는 차단한다.
 *
 * 두 축으로 고정한다:
 *  1) **구조(ArchUnit)**: speech 도메인/application 이 HTTP/URL fetch 타입(java.net.http·java.net.URL·
 *     openConnection 등)에 의존하지 않는다 — 즉 발화 경로에 자동 fetch 코드가 존재하지 않는다.
 *  2) **기능(SSRF)**: knowledge 의 [KnowledgeSourceValidator] 가 localhost·사설망·링크로컬·메타데이터
 *     (169.254.169.254)·비-HTTPS·자격증명 포함 URL 을 차단한다(허용된 adapter 도 내부망에 못 닿는다).
 *
 * acceptance: 허용된 retrieval adapter 만 네트워크 접근을 수행한다(speech 자동 fetch 0, SSRF 차단).
 */
class NexaExternalContentSecurityTest {
    private val central =
        ClassFileImporter()
            .withImportOption(ImportOption.DoNotIncludeTests())
            .importPackages("com.discordassistant.central")

    private val validator = KnowledgeSourceValidator()

    // ── 1) 구조: speech 발화 경로는 URL/HTTP fetch 타입에 의존하지 않는다 ──────────────

    @Test
    fun `speech 는 HTTP 클라이언트 타입에 의존하지 않는다(자동 fetch 금지)`() {
        noClasses()
            .that()
            .resideInAnyPackage("..central.speech..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                "java.net.http..",
                "org.springframework.web.client..",
                "org.springframework.web.reactive.function.client..",
                "okhttp3..",
            ).allowEmptyShould(true)
            .check(central)
    }

    @Test
    fun `speech 는 URL·URLConnection 타입에 의존하지 않는다(메시지 URL 자동 열람 금지)`() {
        noClasses()
            .that()
            .resideInAnyPackage("..central.speech..")
            .should()
            .dependOnClassesThat()
            .haveFullyQualifiedName("java.net.URL")
            .orShould()
            .dependOnClassesThat()
            .haveFullyQualifiedName("java.net.URLConnection")
            .orShould()
            .dependOnClassesThat()
            .haveFullyQualifiedName("java.net.HttpURLConnection")
            .allowEmptyShould(true)
            .check(central)
    }

    // ── 2) 기능: 허용된 retrieval adapter(knowledge)도 SSRF/내부망에 못 닿는다 ──────────

    @Test
    fun `localhost 와 사설망 주소는 SSRF 로 차단된다`() {
        val ssrfUris =
            listOf(
                "https://localhost/secret",
                "https://127.0.0.1/admin",
                "https://10.0.0.5/internal",
                "https://192.168.1.1/router",
                "https://172.16.0.1/private",
                "https://service.internal/data",
                "https://node.local/x",
            )
        ssrfUris.forEach { uri ->
            val result = validator.validateSource(sourceType = "link", sourceUri = uri, contentPreview = "x")
            assertThat(result.initialStatus.kind)
                .`as`("SSRF must block $uri")
                .isEqualTo(KnowledgeSourceStatus.Kind.BLOCKED_SSRF)
        }
    }

    @Test
    fun `클라우드 메타데이터 주소는 차단된다`() {
        // 169.254.169.254 (AWS/GCP/Azure metadata) — 십진/8진/16진 표기 모두 차단.
        val metadataForms =
            listOf(
                "https://169.254.169.254/latest/meta-data/",
                "https://0xa9fea9fe/latest/meta-data/",
                "https://2852039166/latest/meta-data/",
            )
        metadataForms.forEach { uri ->
            val result = validator.validateSource(sourceType = "link", sourceUri = uri, contentPreview = "x")
            assertThat(result.initialStatus.kind)
                .`as`("metadata endpoint must block $uri")
                .isEqualTo(KnowledgeSourceStatus.Kind.BLOCKED_SSRF)
        }
    }

    @Test
    fun `비-HTTPS 와 자격증명 포함 URL 은 거부된다`() {
        val nonHttps = validator.validateSource("link", "http://example.com/x", "x")
        assertThat(nonHttps.initialStatus.kind).isEqualTo(KnowledgeSourceStatus.Kind.BLOCKED_NON_HTTPS)

        val withUserInfo = validator.validateSource("link", "https://user:pass@example.com/x", "x")
        // 자격증명 포함은 민감으로 차단.
        assertThat(withUserInfo.initialStatus.kind).isEqualTo(KnowledgeSourceStatus.Kind.BLOCKED_SENSITIVE)
    }

    @Test
    fun `정상 외부 HTTPS 출처는 통과한다`() {
        val ok = validator.validateSource("link", "https://docs.example.com/guide", "정상 문서")
        // 차단 사유가 아니어야 한다(PENDING/REVIEW 등 정상 흐름).
        assertThat(ok.initialStatus.kind)
            .isNotIn(
                KnowledgeSourceStatus.Kind.BLOCKED_SSRF,
                KnowledgeSourceStatus.Kind.BLOCKED_NON_HTTPS,
                KnowledgeSourceStatus.Kind.BLOCKED_BAD_URI,
            )
    }
}
