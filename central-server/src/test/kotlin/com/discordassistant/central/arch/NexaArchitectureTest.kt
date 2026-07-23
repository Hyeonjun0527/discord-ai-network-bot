package com.discordassistant.central.arch

import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.junit.AnalyzeClasses
import com.tngtech.archunit.junit.ArchTest
import com.tngtech.archunit.lang.ArchRule
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * NEXA 신규 도메인 경계 ArchUnit 규칙(차수 P01-T022/T023).
 *
 * NEXA 5개 도메인(conversation/participation/socialmemory/speech/actionruntime)은 아직 production
 * 코드로 존재하지 않는다. 따라서 production 규칙은 빈 패키지에서 **vacuous pass**(`allowEmptyShould(true)`)
 * 여야 하고, 규칙의 실제 동작은 의도적 위반 fixture(`..arch.nexafixture..`)를 로드해 검증하는
 * self-test `@Test`로 증명한다.
 *
 * 기존 [ArchitectureTest] 9규칙은 건드리지 않는다(baseline separation rule, docs/nexa/baseline/archunit-rules.md).
 * 규칙은 패키지 패턴을 인자로 받는 빌더(`*Rule(...)`)로 두어 production 패키지(이 클래스의 @ArchTest)와
 * fixture 패키지(self-test)가 같은 규칙 정의를 공유한다(DRY).
 */
@AnalyzeClasses(
    packages = ["com.discordassistant.central"],
    importOptions = [ImportOption.DoNotIncludeTests::class],
)
class NexaArchitectureTest {
    // ── production 규칙(빈 NEXA 패키지에서 vacuous pass) ───────────────────────────

    // T022: NEXA 5개 도메인은 순수 Kotlin 규칙만 가진다 — application/adapter/infrastructure 와
    // Spring/JPA/JDA 에 의존하지 않는다(module-dag.md 금지 의존 #5, migratedDomainsArePure 확장).
    @ArchTest
    val nexaDomainsArePure: ArchRule = nexaDomainsArePureRule(*NEXA_DOMAIN_PACKAGES)

    // T022: conversation(관찰)은 하류 participation/speech/actionruntime/socialmemory 를 모른다
    // (module-dag.md 금지 의존 #1).
    @ArchTest
    val conversationDoesNotKnowDownstream: ArchRule =
        conversationDoesNotKnowDownstreamRule(
            sourcePackages = arrayOf("..central.conversation.."),
            downstreamPackages =
                arrayOf(
                    "..central.participation..",
                    "..central.speech..",
                    "..central.actionruntime..",
                    "..central.socialmemory..",
                ),
        )

    // T022: speech 는 routing CloudLlm 포트만 호출한다 — JDA·provider-agent glm·Z.AI SDK 타입에
    // 의존하지 않는다(module-dag.md 금지 의존 #3, speech-context.md).
    @ArchTest
    val speechHasNoForbiddenBackendDependency: ArchRule =
        speechHasNoForbiddenBackendDependencyRule("..central.speech..")

    // T022: participation 은 speech 문장 생성·actionruntime 전송 **구현**(adapter 내부)·JDA 전송에
    // 의존하지 않는다 — 공개 application port 만 호출한다(module-dag.md 금지 의존 #2, participation-context.md,
    // ADR 0008).
    @ArchTest
    val participationDoesNotDependOnDownstreamImplementation: ArchRule =
        participationDoesNotDependOnDownstreamImplementationRule(
            sourcePackages = arrayOf("..central.participation.."),
            forbiddenPackages =
                arrayOf(
                    "..central.speech.adapter..",
                    "..central.actionruntime.adapter..",
                    "net.dv8tion..",
                ),
        )

    // T023: 기존 도메인은 NEXA 를 모른다 — NEXA 신규 도메인의 adapter 내부 구현을 직접 참조하지 않는다.
    // 공개 application port/API 로만 소비해야 한다(module-dag.md 금지 의존 #4, 불변식 2).
    @ArchTest
    val existingDomainsDoNotReachIntoNexaAdapter: ArchRule =
        existingDomainsDoNotReachIntoNexaAdapterRule(
            existingDomainPackages = EXISTING_DOMAIN_PACKAGES,
            nexaAdapterPackages =
                arrayOf(
                    "..central.conversation.adapter..",
                    "..central.participation.adapter..",
                    "..central.socialmemory.adapter..",
                    "..central.speech.adapter..",
                    "..central.actionruntime.adapter..",
                ),
        )

    // P17 보안 enforcement seam: 발화 생성·전송은 **speech-emit seam 경유만** 허용한다 — 외부 GLM 생성 adapter
    // (RoutingCloudSpeechGenerationAdapter)를 seam 밖에서 직접 호출하면 allowlist/critic/consent/고위험 fallback 을
    // 우회할 수 있으므로 구조적으로 금지한다. 그 adapter 는 오직 자기 자신(어댑터 정의 패키지) 안에서만 참조될 수
    // 있고, 다른 모든 코드는 SpeechGenerationPort(포트)·NexaSpeechEmitService(seam)를 거쳐야 한다.
    @ArchTest
    val speechEmitDoesNotBypassGenerationAdapter: ArchRule =
        speechEmitDoesNotBypassGenerationAdapterRule(
            forbiddenAdapterNamePattern = ".*RoutingCloudSpeechGenerationAdapter",
            allowedPackages = arrayOf("..central.speech.adapter.outbound.routing.."),
        )

    // ── self-test: 의도적 위반 fixture 가 규칙에서 실패하는지 검증 ───────────────────

    // T022 acceptance: "의도적 위반 fixture가 테스트에서 실패한다".
    @Test
    fun `nexa domain purity rule fails on framework-dependent fixture`() {
        val fixture = importFixture("com.discordassistant.central.arch.nexafixture.domain")
        assertThatThrownBy { nexaDomainsArePureRule("..nexafixture.domain..").check(fixture) }
            .isInstanceOf(AssertionError::class.java)
    }

    @Test
    fun `conversation rule fails when it depends on downstream fixture`() {
        val fixture =
            importFixture(
                "com.discordassistant.central.arch.nexafixture.conversation",
                "com.discordassistant.central.arch.nexafixture.downstream",
            )
        val rule =
            conversationDoesNotKnowDownstreamRule(
                sourcePackages = arrayOf("..nexafixture.conversation.."),
                downstreamPackages = arrayOf("..nexafixture.downstream.."),
            )
        assertThatThrownBy { rule.check(fixture) }.isInstanceOf(AssertionError::class.java)
    }

    @Test
    fun `speech rule fails on jda and glm-zai dependent fixture`() {
        val fixture = importFixture("com.discordassistant.central.arch.nexafixture.speech")
        assertThatThrownBy {
            speechHasNoForbiddenBackendDependencyRule("..nexafixture.speech..").check(fixture)
        }.isInstanceOf(AssertionError::class.java)
    }

    // 금지 의존 #2 acceptance: participation 이 하류 adapter 구현에 의존하면 탐지된다.
    @Test
    fun `participation rule fails when depending on downstream adapter implementation fixture`() {
        val fixture =
            importFixture(
                "com.discordassistant.central.arch.nexafixture.participationsrc",
                "com.discordassistant.central.arch.nexafixture.downstreamadapter",
            )
        val rule =
            participationDoesNotDependOnDownstreamImplementationRule(
                sourcePackages = arrayOf("..nexafixture.participationsrc.."),
                forbiddenPackages = arrayOf("..nexafixture.downstreamadapter.."),
            )
        assertThatThrownBy { rule.check(fixture) }.isInstanceOf(AssertionError::class.java)
    }

    // T023 acceptance: "공개 application port/API를 우회한 import가 탐지된다".
    @Test
    fun `existing domain rule fails when reaching into nexa adapter fixture`() {
        val fixture =
            importFixture(
                "com.discordassistant.central.arch.nexafixture.existingdomain",
                "com.discordassistant.central.arch.nexafixture.nexaadapter",
            )
        val rule =
            existingDomainsDoNotReachIntoNexaAdapterRule(
                existingDomainPackages = arrayOf("..nexafixture.existingdomain.."),
                nexaAdapterPackages = arrayOf("..nexafixture.nexaadapter.."),
            )
        assertThatThrownBy { rule.check(fixture) }.isInstanceOf(AssertionError::class.java)
    }

    // P17 acceptance: speech-emit seam 우회(외부 GLM 생성 adapter 직접 호출)가 탐지된다.
    @Test
    fun `speech emit bypass rule fails when calling generation adapter directly outside the seam`() {
        val fixture = importFixture("com.discordassistant.central.arch.nexafixture.speechemit")
        // fixture 패키지는 allowed 가 아니므로(어댑터 정의 패키지 밖) adapter 직접 참조가 위반으로 잡힌다.
        val rule =
            speechEmitDoesNotBypassGenerationAdapterRule(
                forbiddenAdapterNamePattern = ".*RoutingCloudSpeechGenerationAdapter",
                allowedPackages = arrayOf("..never.matches.this.package.."),
            )
        assertThatThrownBy { rule.check(fixture) }.isInstanceOf(AssertionError::class.java)
    }

    @Test
    fun `production participation path contains no ensemble or majority vote traces`() {
        val forbidden = Regex("(?i)(3표결|ensemble|majority|tri[- ]?judge|three[- ]?judge|다수결|\\bvoting\\b|\\bvote\\b)")
        val roots =
            listOf(
                Path.of("src", "main", "kotlin", "com", "discordassistant", "central", "participation"),
                Path.of("src", "main", "kotlin", "com", "discordassistant", "central", "platform", "discord", "nexa"),
            )

        val offenders =
            roots
                .flatMap(::kotlinSourceFiles)
                .mapNotNull { path ->
                    val text = Files.readString(path)
                    val match = forbidden.find(text) ?: return@mapNotNull null
                    "${path.toString().removePrefix("src/main/kotlin/")}: ${match.value}"
                }

        assertThat(offenders).isEmpty()
    }

    @Test
    fun `discord inbound message path forwards guild messages to participation before legacy responses`() {
        val source =
            Files.readString(
                Path.of(
                    "src",
                    "main",
                    "kotlin",
                    "com",
                    "discordassistant",
                    "central",
                    "platform",
                    "discord",
                    "DiscordBot.kt",
                ),
            )

        val onMessageReceived = source.substringBetween("override fun onMessageReceived", "private fun forwardToParticipation")
        val botFilter = onMessageReceived.indexOf("if (event.author.isBot) {")
        val participationForward =
            onMessageReceived.indexOf(
                "forwardToParticipation(event, mentioned || directlyAddressed, rawContextPreCaptured)",
            )
        val participationOwnershipReturn = onMessageReceived.indexOf("if (participationTurn.ownsTurn) return")
        val mentionResponse = onMessageReceived.indexOf("if (mentioned)")
        val autoRespond = onMessageReceived.indexOf("handleAutoRespond(event)")

        assertThat(participationForward).isGreaterThan(botFilter)
        assertThat(participationOwnershipReturn).isGreaterThan(participationForward)
        assertThat(participationOwnershipReturn).isLessThan(mentionResponse)
        assertThat(participationOwnershipReturn).isLessThan(autoRespond)

        val forwardToParticipation = source.substringBetween("private fun forwardToParticipation", "private fun participationSourceTypeOf")
        val bridgeCall = forwardToParticipation.indexOf("participationEmitBridge.onMessageTurn(")
        assertThat(forwardToParticipation.indexOf("ParticipationMessageSignal(")).isGreaterThan(bridgeCall)
        assertThat(forwardToParticipation).contains(
            "triggerText = contentRaw.take(500)",
            "rawText = contentRaw",
            "sourceType = participationSourceTypeOf(event)",
        )
    }

    companion object {
        // NEXA 5개 도메인의 domain 레이어 패키지.
        private val NEXA_DOMAIN_PACKAGES =
            arrayOf(
                "..central.conversation.domain..",
                "..central.participation.domain..",
                "..central.socialmemory.domain..",
                "..central.speech.domain..",
                "..central.actionruntime.domain..",
            )

        // 기존 도메인(routing/platform/channelai/ainetwork 등) — NEXA adapter 역참조 금지 대상.
        private val EXISTING_DOMAIN_PACKAGES =
            arrayOf(
                "..central.routing..",
                "..central.platform..",
                "..central.channelai..",
                "..central.ainetwork..",
                "..central.guild..",
                "..central.knowledge..",
                "..central.onboarding..",
                "..central.multiresponse..",
                "..central.preset..",
                "..central.licensing..",
                "..central.provider..",
                "..central.quota..",
                "..central.requestlog..",
            )

        // module-dag.md 금지 의존 #5 — 도메인은 프레임워크/하위 레이어에 의존하지 않는다.
        private fun nexaDomainsArePureRule(vararg domainPackages: String): ArchRule =
            noClasses()
                .that()
                .resideInAnyPackage(*domainPackages)
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                    "..application..",
                    "..adapter..",
                    "..infrastructure..",
                    "org.springframework..",
                    "jakarta.persistence..",
                    "net.dv8tion..",
                ).allowEmptyShould(true)

        // module-dag.md 금지 의존 #1 — conversation(관찰)은 하류를 모른다.
        private fun conversationDoesNotKnowDownstreamRule(
            sourcePackages: Array<String>,
            downstreamPackages: Array<String>,
        ): ArchRule =
            noClasses()
                .that()
                .resideInAnyPackage(*sourcePackages)
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(*downstreamPackages)
                .allowEmptyShould(true)

        // module-dag.md 금지 의존 #3 — speech 는 JDA·provider-agent glm·Z.AI SDK 타입에 의존하지 않는다.
        private fun speechHasNoForbiddenBackendDependencyRule(vararg speechPackages: String): ArchRule =
            noClasses()
                .that()
                .resideInAnyPackage(*speechPackages)
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage("net.dv8tion..")
                .orShould()
                .dependOnClassesThat()
                .haveNameMatching(".*([Gg]lm|[Zz]ai).*")
                .allowEmptyShould(true)

        // module-dag.md 금지 의존 #2 — participation 은 speech/actionruntime adapter 구현·JDA 전송에
        // 의존하지 않는다(공개 application port 만 허용).
        private fun participationDoesNotDependOnDownstreamImplementationRule(
            sourcePackages: Array<String>,
            forbiddenPackages: Array<String>,
        ): ArchRule =
            noClasses()
                .that()
                .resideInAnyPackage(*sourcePackages)
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(*forbiddenPackages)
                .allowEmptyShould(true)

        // module-dag.md 금지 의존 #4 / 불변식 2 — 기존 도메인은 NEXA adapter 내부를 직접 참조하지 않는다.
        private fun existingDomainsDoNotReachIntoNexaAdapterRule(
            existingDomainPackages: Array<String>,
            nexaAdapterPackages: Array<String>,
        ): ArchRule =
            noClasses()
                .that()
                .resideInAnyPackage(*existingDomainPackages)
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(*nexaAdapterPackages)
                .allowEmptyShould(true)

        // P17 보안 enforcement seam — 외부 GLM 생성 adapter([forbiddenAdapterNamePattern], 예:
        // RoutingCloudSpeechGenerationAdapter)는 자기 정의 패키지([allowedPackages]) 안에서만 참조될 수 있다.
        // 그 밖의 어떤 클래스가 이 adapter 를 직접 참조하면(= speech-emit seam·SpeechGenerationPort 우회) 위반이다 —
        // allowlist/critic/consent/고위험 fallback 을 건너뛸 수 있기 때문. 우회를 구조적으로 차단한다.
        private fun speechEmitDoesNotBypassGenerationAdapterRule(
            forbiddenAdapterNamePattern: String,
            allowedPackages: Array<String>,
        ): ArchRule =
            noClasses()
                .that()
                .resideOutsideOfPackages(*allowedPackages)
                .should()
                .dependOnClassesThat()
                .haveNameMatching(forbiddenAdapterNamePattern)
                .allowEmptyShould(true)

        // fixture 패키지는 test 소스라 @AnalyzeClasses(DoNotIncludeTests)에 안 잡힌다 — 명시 로드.
        private fun importFixture(vararg packages: String) = ClassFileImporter().importPackages(*packages)

        private fun kotlinSourceFiles(root: Path): List<Path> {
            if (!Files.exists(root)) return emptyList()
            Files.walk(root).use { paths ->
                return paths
                    .filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".kt") }
                    .toList()
            }
        }

        private fun String.substringBetween(
            start: String,
            end: String,
        ): String {
            val startIndex = indexOf(start)
            val endIndex = indexOf(end, startIndex + start.length)
            assertThat(startIndex).isGreaterThanOrEqualTo(0)
            assertThat(endIndex).isGreaterThan(startIndex)
            return substring(startIndex, endIndex)
        }
    }
}
