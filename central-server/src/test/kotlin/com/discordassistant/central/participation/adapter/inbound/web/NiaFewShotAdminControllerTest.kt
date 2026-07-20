package com.discordassistant.central.participation.adapter.inbound.web

import com.discordassistant.central.global.adapter.inbound.web.GlobalExceptionHandler
import com.discordassistant.central.global.security.DashboardActor
import com.discordassistant.central.participation.application.fewshot.NiaFewShotEvalService
import com.discordassistant.central.participation.application.fewshot.NiaFewShotService
import com.discordassistant.central.participation.application.port.out.NiaFewShotStorePort
import com.discordassistant.central.participation.domain.model.fewshot.NiaFewShotExample
import com.discordassistant.central.participation.domain.model.fewshot.NiaFewShotLookupScope
import com.discordassistant.central.participation.domain.model.fewshot.NiaFewShotScope
import com.discordassistant.central.participation.domain.model.fewshot.NiaFewShotSet
import com.discordassistant.central.participation.domain.model.fewshot.NiaFewShotVersion
import com.discordassistant.central.platform.discord.nexa.NexaBuiltInFewShotCatalogAdapter
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.time.Instant

class NiaFewShotAdminControllerTest {
    private val store = InMemoryFewShotStore()
    private val mapper: ObjectMapper = jacksonObjectMapper()
    private val mockMvc: MockMvc =
        MockMvcBuilders
            .standaloneSetup(
                NiaFewShotAdminController(
                    NiaFewShotService(store, NiaFewShotEvalService()),
                    NexaBuiltInFewShotCatalogAdapter(),
                ),
            ).setControllerAdvice(GlobalExceptionHandler())
            .build()

    @Test
    fun `draft can be created previewed evaluated and published`() {
        val created =
            mockMvc
                .perform(
                    post("/api/admin/nia/few-shot/sets")
                        .requestAttr(DashboardActor.REQUEST_ATTRIBUTE, DashboardActor(userId = 123, systemToken = false))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(draftBody(seedExamplesJson())),
                ).andExpect(status().isOk)
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.examples[0].expectedAction").value("SPEAK"))
                .andReturn()
                .response
                .contentAsString

        val version = mapper.readTree(created)
        val setId = version["setId"].asLong()

        mockMvc
            .perform(
                post("/api/admin/nia/few-shot/sets/$setId/drafts/1/preview")
                    .requestAttr(DashboardActor.REQUEST_ATTRIBUTE, DashboardActor(userId = 123, systemToken = false))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"redactRawText":true}"""),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.schema").value("nia.participation-judge-input.v1"))
            .andExpect(jsonPath("$.fewShotSet.examples[0].rawMessages[0].text").value("[redacted]"))
            .andExpect(jsonPath("$.fewShotSet.examples[10].expectedReevaluateAfterMs").value(1_500))
            .andExpect(jsonPath("$.fewShotSet.examples[19].expectedReactionCode").value("eyes"))
            .andExpect(jsonPath("$.fewShotSet.examples[35].currentState").value("A pending action is no longer valid."))

        mockMvc
            .perform(
                post("/api/admin/nia/few-shot/sets/$setId/drafts/1/eval")
                    .requestAttr(DashboardActor.REQUEST_ATTRIBUTE, DashboardActor(userId = 123, systemToken = false)),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("PASS"))
            .andExpect(jsonPath("$.readyForPublish").value(true))
            .andExpect(jsonPath("$.checkedExamples").value(40))
            .andExpect(jsonPath("$.hardAmbiguousCount").value(7))

        mockMvc
            .perform(
                post("/api/admin/nia/few-shot/sets/$setId/versions/1/publish")
                    .requestAttr(DashboardActor.REQUEST_ATTRIBUTE, DashboardActor(userId = 123, systemToken = false)),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.activeVersion").value(1))
            .andExpect(jsonPath("$.versions[0].status").value("ACTIVE"))
    }

    @Test
    fun `draft examples can be replaced but published versions are immutable`() {
        val created =
            mockMvc
                .perform(
                    post("/api/admin/nia/few-shot/sets")
                        .requestAttr(DashboardActor.REQUEST_ATTRIBUTE, DashboardActor(userId = null, systemToken = true))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(draftBody(exampleJson("WAIT"))),
                ).andReturn()
                .response
                .contentAsString
        val setId = mapper.readTree(created)["setId"].asLong()

        mockMvc
            .perform(
                put("/api/admin/nia/few-shot/sets/$setId/drafts/1")
                    .requestAttr(DashboardActor.REQUEST_ATTRIBUTE, DashboardActor(userId = null, systemToken = true))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"examples":[${seedExamplesJson()}]}"""),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.examples.length()").value(40))

        mockMvc
            .perform(
                post("/api/admin/nia/few-shot/sets/$setId/versions/1/publish")
                    .requestAttr(DashboardActor.REQUEST_ATTRIBUTE, DashboardActor(userId = null, systemToken = true)),
            ).andExpect(status().isOk)

        mockMvc
            .perform(
                put("/api/admin/nia/few-shot/sets/$setId/drafts/1")
                    .requestAttr(DashboardActor.REQUEST_ATTRIBUTE, DashboardActor(userId = null, systemToken = true))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"examples":[${exampleJson("WAIT")}]}"""),
            ).andExpect(status().isBadRequest)
    }

    @Test
    fun `small curated draft can pass eval and publish`() {
        val created =
            mockMvc
                .perform(
                    post("/api/admin/nia/few-shot/sets")
                        .requestAttr(DashboardActor.REQUEST_ATTRIBUTE, DashboardActor(userId = 123, systemToken = false))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(draftBody(exampleJson("SPEAK"))),
                ).andReturn()
                .response
                .contentAsString
        val setId = mapper.readTree(created)["setId"].asLong()

        mockMvc
            .perform(
                post("/api/admin/nia/few-shot/sets/$setId/drafts/1/eval")
                    .requestAttr(DashboardActor.REQUEST_ATTRIBUTE, DashboardActor(userId = 123, systemToken = false)),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("PASS"))
            .andExpect(jsonPath("$.readyForPublish").value(true))

        mockMvc
            .perform(
                post("/api/admin/nia/few-shot/sets/$setId/versions/1/publish")
                    .requestAttr(DashboardActor.REQUEST_ATTRIBUTE, DashboardActor(userId = 123, systemToken = false)),
            ).andExpect(status().isOk)
    }

    @Test
    fun `missing dashboard actor fails closed`() {
        mockMvc
            .perform(get("/api/admin/nia/few-shot/sets"))
            .andExpect(status().isInternalServerError)
            .andExpect(jsonPath("$.error.code").value("INVALID_SERVER_STATE"))
    }

    @Test
    fun `effective endpoint exposes runtime built in examples when no managed global set exists`() {
        mockMvc
            .perform(
                get("/api/admin/nia/few-shot/effective")
                    .requestAttr(DashboardActor.REQUEST_ATTRIBUTE, DashboardActor(userId = 123, systemToken = false)),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.judgeSource").value("BUILT_IN_FALLBACK"))
            .andExpect(jsonPath("$.builtInJudgeSetId").value(9_000_000_000_001L))
            .andExpect(jsonPath("$.builtInJudgeExamples.length()").value(11))
            .andExpect(jsonPath("$.builtInSpeechExamples.length()").value(4))
            .andExpect(jsonPath("$.managedGlobalExamples.length()").value(0))
    }

    private fun draftBody(examplesJson: String): String =
        """
        {
          "scope": {"type":"GLOBAL","persona":"nia"},
          "examples": [$examplesJson]
        }
        """.trimIndent()

    private fun seedExamplesJson(): String {
        val actions =
            List(10) { "SPEAK" } +
                List(9) { "WAIT" } +
                List(6) { "REACT" } +
                List(10) { "IGNORE" } +
                List(5) { "CANCEL" }
        return actions
            .mapIndexed { index, action -> seedExampleJson(index, action) }
            .joinToString(",")
    }

    private fun seedExampleJson(
        index: Int,
        expectedAction: String,
    ): String {
        val tags =
            buildList {
                if (index < 7) add("hard-ambiguous")
                if (index == 0) add("missed-reply-risk")
                if (index == 10) add("over-talk-risk")
                if (index == 25) add("stale-memory-override")
            }
        val stale = "stale-memory-override" in tags
        val messages =
            if (stale) {
                listOf(
                    mapOf("ref" to "m1", "authorRole" to "member", "offsetMs" to -1000, "text" to "old context"),
                    mapOf("ref" to "m2", "authorRole" to "member", "offsetMs" to 0, "text" to "current correction"),
                )
            } else {
                listOf(mapOf("ref" to "m1", "authorRole" to "member", "offsetMs" to 0, "text" to "synthetic seed $index"))
            }
        val badAction = if (expectedAction == "SPEAK") "WAIT" else "SPEAK"
        return mapper.writeValueAsString(
            mapOf(
                "title" to "seed example $index",
                "rawMessages" to messages,
                "expectedAction" to expectedAction,
                "expectedDeliveryMode" to if (expectedAction == "SPEAK") "CHANNEL" else null,
                "currentState" to if (expectedAction == "CANCEL") "A pending action is no longer valid." else null,
                "expectedReactionCode" to if (expectedAction == "REACT") "eyes" else null,
                "expectedReevaluateAfterMs" to if (expectedAction == "WAIT") 1_500 else null,
                "reason" to "Synthetic seed reason for $expectedAction.",
                "evidenceRefs" to listOf(if (stale) "m2" else "m1"),
                "badAlternative" to mapOf("action" to badAction, "whyBad" to "It would choose the wrong participation action."),
                "tags" to tags,
                "priority" to (100 - index),
                "privacyClass" to "SYNTHETIC",
            ),
        )
    }

    private fun exampleJson(expectedAction: String): String {
        val badAction =
            when (expectedAction) {
                "WAIT" -> "SPEAK"
                else -> "WAIT"
            }
        return mapper.writeValueAsString(
            mapOf(
                "title" to "direct reply request",
                "rawMessages" to
                    listOf(
                        mapOf(
                            "ref" to "m1",
                            "authorRole" to "member",
                            "offsetMs" to 0,
                            "text" to "야 대답해줘",
                        ),
                    ),
                "expectedAction" to expectedAction,
                "expectedDeliveryMode" to if (expectedAction == "SPEAK") "CHANNEL" else null,
                "currentState" to if (expectedAction == "CANCEL") "A pending action is no longer valid." else null,
                "expectedReactionCode" to if (expectedAction == "REACT") "eyes" else null,
                "expectedReevaluateAfterMs" to if (expectedAction == "WAIT") 1_500 else null,
                "reason" to "The judge should use the raw message as evidence.",
                "evidenceRefs" to listOf("m1"),
                "badAlternative" to mapOf("action" to badAction, "whyBad" to "It ignores the evidence."),
                "tags" to listOf("direct-ask"),
                "priority" to 10,
            ),
        )
    }
}

private class InMemoryFewShotStore : NiaFewShotStorePort {
    private val sets = linkedMapOf<Long, NiaFewShotSet>()
    private var nextSetId = 1L
    private var nextVersionId = 1L

    override fun listSets(limit: Int): List<NiaFewShotSet> = sets.values.take(limit)

    override fun findSet(setId: Long): NiaFewShotSet? = sets[setId]

    override fun findActive(lookup: NiaFewShotLookupScope): NiaFewShotSet? =
        lookup.candidates().firstNotNullOfOrNull { scope -> findByScope(scope)?.takeIf { it.active != null } }

    override fun findByScope(scope: NiaFewShotScope): NiaFewShotSet? = sets.values.firstOrNull { it.scope.stableKey == scope.stableKey }

    override fun findVersion(
        setId: Long,
        version: Int,
    ): NiaFewShotVersion? = sets[setId]?.versions?.firstOrNull { it.version == version }

    override fun createDraft(
        scope: NiaFewShotScope,
        examples: List<NiaFewShotExample>,
        actorUserId: Long?,
    ): NiaFewShotVersion {
        val now = Instant.parse("2026-06-30T00:00:00Z")
        val existing = findByScope(scope)
        val setId = existing?.id ?: nextSetId++
        val versionNumber = (existing?.versions?.maxOfOrNull { it.version } ?: 0) + 1
        val version =
            NiaFewShotVersion(
                id = nextVersionId++,
                setId = setId,
                version = versionNumber,
                status = com.discordassistant.central.participation.domain.model.fewshot.NiaFewShotVersionStatus.DRAFT,
                examples = examples,
                createdBy = actorUserId,
                reviewedBy = null,
                publishedAt = null,
                rollbackOfVersion = null,
                createdAt = now,
                updatedAt = now,
            )
        sets[setId] =
            NiaFewShotSet(
                id = setId,
                scope = scope,
                activeVersion = existing?.activeVersion,
                versions = (existing?.versions ?: emptyList()) + version,
                createdAt = existing?.createdAt ?: now,
                updatedAt = now,
            )
        return version
    }

    override fun replaceDraftExamples(
        setId: Long,
        version: Int,
        examples: List<NiaFewShotExample>,
    ): NiaFewShotVersion {
        val set = sets.getValue(setId)
        val target = set.versions.first { it.version == version }
        require(target.status == com.discordassistant.central.participation.domain.model.fewshot.NiaFewShotVersionStatus.DRAFT) {
            "published_or_archived_version_is_immutable"
        }
        val updated = target.copy(examples = examples)
        replaceVersion(set, updated)
        return updated
    }

    override fun publish(
        setId: Long,
        version: Int,
        reviewerUserId: Long?,
    ): NiaFewShotSet {
        val set = sets.getValue(setId)
        val now = Instant.parse("2026-06-30T00:01:00Z")
        val updatedVersions =
            set.versions.map {
                when {
                    it.version == version ->
                        it.copy(
                            status = com.discordassistant.central.participation.domain.model.fewshot.NiaFewShotVersionStatus.ACTIVE,
                            reviewedBy = reviewerUserId,
                            publishedAt = now,
                            updatedAt = now,
                        )
                    it.version == set.activeVersion ->
                        it.copy(status = com.discordassistant.central.participation.domain.model.fewshot.NiaFewShotVersionStatus.ARCHIVED)
                    else -> it
                }
            }
        val updated = set.copy(activeVersion = version, versions = updatedVersions, updatedAt = now)
        sets[setId] = updated
        return updated
    }

    override fun rollback(
        setId: Long,
        targetVersion: Int,
        reviewerUserId: Long?,
    ): NiaFewShotSet = publish(setId, targetVersion, reviewerUserId)

    override fun archive(
        setId: Long,
        version: Int,
    ): NiaFewShotVersion {
        val set = sets.getValue(setId)
        val target =
            set
                .versions
                .first { it.version == version }
                .copy(status = com.discordassistant.central.participation.domain.model.fewshot.NiaFewShotVersionStatus.ARCHIVED)
        replaceVersion(set, target)
        return target
    }

    private fun replaceVersion(
        set: NiaFewShotSet,
        version: NiaFewShotVersion,
    ) {
        sets[set.id!!] = set.copy(versions = set.versions.map { if (it.version == version.version) version else it })
    }
}
