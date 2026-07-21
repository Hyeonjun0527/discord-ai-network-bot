package com.discordassistant.central.participation.adapter.inbound.web

import com.discordassistant.central.global.security.DashboardActor
import com.discordassistant.central.participation.application.fewshot.ArchiveNiaFewShotVersionCommand
import com.discordassistant.central.participation.application.fewshot.CreateNiaFewShotDraftCommand
import com.discordassistant.central.participation.application.fewshot.CreateNiaFewShotDraftForSetCommand
import com.discordassistant.central.participation.application.fewshot.NiaBuiltInFewShotCatalogPort
import com.discordassistant.central.participation.application.fewshot.NiaFewShotEvalResult
import com.discordassistant.central.participation.application.fewshot.NiaFewShotService
import com.discordassistant.central.participation.application.fewshot.PublishNiaFewShotVersionCommand
import com.discordassistant.central.participation.application.fewshot.ReplaceNiaFewShotDraftCommand
import com.discordassistant.central.participation.application.fewshot.RollbackNiaFewShotVersionCommand
import com.discordassistant.central.participation.domain.model.fewshot.NiaFewShotAction
import com.discordassistant.central.participation.domain.model.fewshot.NiaFewShotBadAlternative
import com.discordassistant.central.participation.domain.model.fewshot.NiaFewShotDeliveryMode
import com.discordassistant.central.participation.domain.model.fewshot.NiaFewShotEvalStatus
import com.discordassistant.central.participation.domain.model.fewshot.NiaFewShotExample
import com.discordassistant.central.participation.domain.model.fewshot.NiaFewShotPrivacyClass
import com.discordassistant.central.participation.domain.model.fewshot.NiaFewShotRawMessage
import com.discordassistant.central.participation.domain.model.fewshot.NiaFewShotScope
import com.discordassistant.central.participation.domain.model.fewshot.NiaFewShotScopeType
import com.discordassistant.central.participation.domain.model.fewshot.NiaFewShotSet
import com.discordassistant.central.participation.domain.model.fewshot.NiaFewShotVersion
import jakarta.servlet.http.HttpServletRequest
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

@RestController
@RequestMapping("/api/admin/nia/few-shot")
class NiaFewShotAdminController(
    private val service: NiaFewShotService,
    private val builtInCatalog: NiaBuiltInFewShotCatalogPort? = null,
) {
    @GetMapping("/effective")
    fun effective(httpRequest: HttpServletRequest): NiaEffectiveFewShotDto {
        DashboardActor.from(httpRequest)
        val builtIn = builtInCatalog?.catalog()
        val managedGlobal = service.exactScope(NiaFewShotScope.global())
        val managedActive = managedGlobal?.active
        return NiaEffectiveFewShotDto(
            judgeSource = if (managedActive == null) "BUILT_IN_FALLBACK" else "MANAGED_GLOBAL",
            builtInJudgeSetId = builtIn?.judgeSetId,
            builtInJudgeVersion = builtIn?.judgeVersion,
            builtInJudgeExamples = builtIn?.judgeExamples.orEmpty().map { it.toDto(redactRawText = false) },
            builtInSpeechExamples =
                builtIn
                    ?.speechExamples
                    .orEmpty()
                    .map { example ->
                        NiaBuiltInSpeechExampleDto(
                            title = example.title,
                            messages = example.messages,
                            goodReplies = example.goodReplies,
                            badReplies = example.badReplies,
                        )
                    },
            managedGlobalSetId = managedGlobal?.id,
            managedGlobalVersion = managedActive?.version,
            managedGlobalExamples = managedActive?.examples.orEmpty().map { it.toDto(redactRawText = false) },
            editableDefaultExamples = builtIn?.editableExamples.orEmpty().map { it.toDto(redactRawText = false) },
        )
    }

    @GetMapping("/sets")
    fun sets(
        @RequestParam(required = false) scopeType: String?,
        @RequestParam(required = false) guildId: Long?,
        @RequestParam(required = false) channelId: Long?,
        @RequestParam(required = false) persona: String?,
        @RequestParam(required = false) limit: Int?,
        httpRequest: HttpServletRequest,
    ): List<NiaFewShotSetDto> {
        DashboardActor.from(httpRequest)
        val hasScopeFilter = scopeType != null || guildId != null || channelId != null || persona != null
        return if (hasScopeFilter) {
            listOfNotNull(service.exactScope(scopeFromQuery(scopeType, guildId, channelId, persona))?.toDto(redactRawText = false))
        } else {
            service.listSets(limit ?: 100).map { it.toDto(redactRawText = false) }
        }
    }

    @PostMapping("/sets")
    fun createSetDraft(
        @RequestBody request: NiaFewShotCreateDraftRequest,
        httpRequest: HttpServletRequest,
    ): NiaFewShotVersionDto {
        val actor = DashboardActor.from(httpRequest)
        return service
            .createDraft(
                CreateNiaFewShotDraftCommand(
                    scope = request.scope.toDomain(),
                    examples = request.examples.map { it.toDomain() },
                    actorUserId = actor.userId,
                ),
            ).toDto(redactRawText = false)
    }

    @GetMapping("/sets/{setId}/versions/{version}")
    fun version(
        @PathVariable setId: Long,
        @PathVariable version: Int,
        httpRequest: HttpServletRequest,
    ): NiaFewShotVersionDto {
        DashboardActor.from(httpRequest)
        return service.version(setId, version).toDto(redactRawText = false)
    }

    @PostMapping("/sets/{setId}/drafts")
    fun createDraftForSet(
        @PathVariable setId: Long,
        @RequestBody request: NiaFewShotDraftExamplesRequest,
        httpRequest: HttpServletRequest,
    ): NiaFewShotVersionDto {
        val actor = DashboardActor.from(httpRequest)
        return service
            .createDraftForSet(
                CreateNiaFewShotDraftForSetCommand(
                    setId = setId,
                    examples = request.examples.map { it.toDomain() },
                    actorUserId = actor.userId,
                ),
            ).toDto(redactRawText = false)
    }

    @PutMapping("/sets/{setId}/drafts/{version}")
    fun replaceDraft(
        @PathVariable setId: Long,
        @PathVariable version: Int,
        @RequestBody request: NiaFewShotDraftExamplesRequest,
        httpRequest: HttpServletRequest,
    ): NiaFewShotVersionDto {
        DashboardActor.from(httpRequest)
        return service
            .replaceDraft(
                ReplaceNiaFewShotDraftCommand(
                    setId = setId,
                    version = version,
                    examples = request.examples.map { it.toDomain() },
                ),
            ).toDto(redactRawText = false)
    }

    @PostMapping("/sets/{setId}/drafts/{version}/preview")
    fun preview(
        @PathVariable setId: Long,
        @PathVariable version: Int,
        @RequestBody request: NiaFewShotPreviewRequest,
        httpRequest: HttpServletRequest,
    ): NiaFewShotPromptPreviewDto {
        DashboardActor.from(httpRequest)
        val target = service.version(setId, version)
        return NiaFewShotPromptPreviewDto.from(target, redactRawText = request.redactRawText)
    }

    @PostMapping("/sets/{setId}/drafts/{version}/eval")
    fun eval(
        @PathVariable setId: Long,
        @PathVariable version: Int,
        httpRequest: HttpServletRequest,
    ): NiaFewShotEvalDto {
        DashboardActor.from(httpRequest)
        return NiaFewShotEvalDto.from(service.evaluate(setId, version))
    }

    @PostMapping("/sets/{setId}/versions/{version}/publish")
    fun publish(
        @PathVariable setId: Long,
        @PathVariable version: Int,
        httpRequest: HttpServletRequest,
    ): NiaFewShotSetDto {
        val actor = DashboardActor.from(httpRequest)
        return service
            .publish(PublishNiaFewShotVersionCommand(setId = setId, version = version, reviewerUserId = actor.userId))
            .toDto(redactRawText = false)
    }

    @PostMapping("/sets/{setId}/versions/{version}/rollback")
    fun rollback(
        @PathVariable setId: Long,
        @PathVariable version: Int,
        httpRequest: HttpServletRequest,
    ): NiaFewShotSetDto {
        val actor = DashboardActor.from(httpRequest)
        return service
            .rollback(RollbackNiaFewShotVersionCommand(setId = setId, targetVersion = version, reviewerUserId = actor.userId))
            .toDto(redactRawText = false)
    }

    @PostMapping("/sets/{setId}/versions/{version}/archive")
    fun archive(
        @PathVariable setId: Long,
        @PathVariable version: Int,
        httpRequest: HttpServletRequest,
    ): NiaFewShotVersionDto {
        DashboardActor.from(httpRequest)
        return service.archive(ArchiveNiaFewShotVersionCommand(setId = setId, version = version)).toDto(redactRawText = false)
    }

    private fun scopeFromQuery(
        scopeType: String?,
        guildId: Long?,
        channelId: Long?,
        persona: String?,
    ): NiaFewShotScope {
        val resolvedType =
            scopeType
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?.let { NiaFewShotScopeType.valueOf(it.uppercase()) }
                ?: when {
                    guildId != null && channelId != null -> NiaFewShotScopeType.CHANNEL
                    guildId != null -> NiaFewShotScopeType.GUILD
                    !persona.isNullOrBlank() -> NiaFewShotScopeType.PERSONA
                    else -> NiaFewShotScopeType.GLOBAL
                }
        return NiaFewShotScopeDto(
            type = resolvedType.name,
            guildId = guildId,
            channelId = channelId,
            persona = persona,
        ).toDomain()
    }
}

data class NiaFewShotCreateDraftRequest(
    val scope: NiaFewShotScopeDto,
    val examples: List<NiaFewShotExampleDto>,
)

data class NiaFewShotDraftExamplesRequest(
    val examples: List<NiaFewShotExampleDto>,
)

data class NiaFewShotPreviewRequest(
    val redactRawText: Boolean = false,
)

data class NiaFewShotScopeDto(
    val type: String,
    val guildId: Long? = null,
    val channelId: Long? = null,
    val persona: String? = null,
) {
    fun toDomain(): NiaFewShotScope =
        NiaFewShotScope(
            type = NiaFewShotScopeType.valueOf(type.trim().uppercase()),
            guildId = guildId,
            channelId = channelId,
            persona = persona?.trim()?.takeIf { it.isNotBlank() } ?: NiaFewShotScope.DEFAULT_PERSONA,
        )
}

data class NiaFewShotExampleDto(
    val id: Long? = null,
    val title: String,
    val rawMessages: List<NiaFewShotRawMessageDto>,
    val expectedAction: String,
    val expectedDeliveryMode: String? = null,
    val expectedReplies: List<String> = emptyList(),
    val badReplies: List<String> = emptyList(),
    val currentState: String? = null,
    val expectedReactionCode: String? = null,
    val expectedReevaluateAfterMs: Long? = null,
    val reason: String,
    val evidenceRefs: Set<String>,
    val badAlternative: NiaFewShotBadAlternativeDto,
    val tags: Set<String> = emptySet(),
    val priority: Int = 0,
    val privacyClass: String = NiaFewShotPrivacyClass.SYNTHETIC.name,
    val evalStatus: String = NiaFewShotEvalStatus.NOT_RUN.name,
) {
    fun toDomain(): NiaFewShotExample =
        NiaFewShotExample(
            id = id,
            title = title,
            rawMessages = rawMessages.map { it.toDomain() },
            expectedAction = NiaFewShotAction.valueOf(expectedAction.trim().uppercase()),
            expectedDeliveryMode = expectedDeliveryMode?.trim()?.uppercase()?.let(NiaFewShotDeliveryMode::valueOf),
            expectedReplies = expectedReplies,
            badReplies = badReplies,
            currentState = currentState,
            expectedReactionCode = expectedReactionCode?.trim()?.lowercase(),
            expectedReevaluateAfterMs = expectedReevaluateAfterMs,
            reason = reason,
            evidenceRefs = evidenceRefs,
            badAlternative = badAlternative.toDomain(),
            tags = tags,
            priority = priority,
            privacyClass = NiaFewShotPrivacyClass.valueOf(privacyClass.trim().uppercase()),
            evalStatus = NiaFewShotEvalStatus.valueOf(evalStatus.trim().uppercase()),
        )
}

data class NiaFewShotRawMessageDto(
    val ref: String,
    val authorRole: String,
    val offsetMs: Long,
    val text: String,
) {
    fun toDomain(): NiaFewShotRawMessage =
        NiaFewShotRawMessage(
            ref = ref,
            authorRole = authorRole,
            offsetMs = offsetMs,
            text = text,
        )
}

data class NiaFewShotBadAlternativeDto(
    val action: String,
    val whyBad: String,
    val deliveryMode: String? = null,
) {
    fun toDomain(): NiaFewShotBadAlternative =
        NiaFewShotBadAlternative(
            action = NiaFewShotAction.valueOf(action.trim().uppercase()),
            deliveryMode = deliveryMode?.trim()?.uppercase()?.let(NiaFewShotDeliveryMode::valueOf),
            whyBad = whyBad,
        )
}

data class NiaFewShotSetDto(
    val id: Long?,
    val scope: NiaFewShotScopeDto,
    val activeVersion: Int?,
    val versions: List<NiaFewShotVersionDto>,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class NiaEffectiveFewShotDto(
    val judgeSource: String,
    val builtInJudgeSetId: Long?,
    val builtInJudgeVersion: Int?,
    val builtInJudgeExamples: List<NiaFewShotExampleDto>,
    val builtInSpeechExamples: List<NiaBuiltInSpeechExampleDto>,
    val managedGlobalSetId: Long?,
    val managedGlobalVersion: Int?,
    val managedGlobalExamples: List<NiaFewShotExampleDto>,
    val editableDefaultExamples: List<NiaFewShotExampleDto>,
)

data class NiaBuiltInSpeechExampleDto(
    val title: String,
    val messages: List<String>,
    val goodReplies: List<String>,
    val badReplies: List<String>,
)

data class NiaFewShotVersionDto(
    val id: Long?,
    val setId: Long?,
    val version: Int,
    val status: String,
    val examples: List<NiaFewShotExampleDto>,
    val createdBy: Long?,
    val reviewedBy: Long?,
    val publishedAt: Instant?,
    val rollbackOfVersion: Int?,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class NiaFewShotPromptPreviewDto(
    val schema: String,
    val sceneWindow: NiaPromptSceneWindowDto,
    val fewShotSet: NiaPromptFewShotSetDto,
    val socialMemory: NiaPromptSocialMemoryDto,
    val metadata: Map<String, Any?>,
) {
    companion object {
        fun from(
            version: NiaFewShotVersion,
            redactRawText: Boolean,
        ): NiaFewShotPromptPreviewDto =
            NiaFewShotPromptPreviewDto(
                schema = "nia.participation-judge-input.v1",
                sceneWindow = NiaPromptSceneWindowDto(maxChars = 200_000, messages = emptyList()),
                fewShotSet =
                    NiaPromptFewShotSetDto(
                        setId = version.setId,
                        version = version.version,
                        examples = version.examples.map { it.toDto(redactRawText) },
                    ),
                socialMemory = NiaPromptSocialMemoryDto(items = emptyList()),
                metadata =
                    mapOf(
                        "consent" to "admin_preview",
                        "recentNiaActions" to emptyList<String>(),
                        "channelMode" to "participation",
                        "runtimeFlags" to emptyMap<String, String>(),
                    ),
            )
    }
}

data class NiaPromptSceneWindowDto(
    val maxChars: Int,
    val messages: List<NiaFewShotRawMessageDto>,
)

data class NiaPromptFewShotSetDto(
    val setId: Long?,
    val version: Int,
    val examples: List<NiaFewShotExampleDto>,
)

data class NiaPromptSocialMemoryDto(
    val items: List<Map<String, Any?>>,
)

data class NiaFewShotEvalDto(
    val status: String,
    val readyForPublish: Boolean,
    val checkedExamples: Int,
    val actionCoverage: Map<String, Int>,
    val hardAmbiguousCount: Int,
    val failures: List<String>,
) {
    companion object {
        fun from(result: NiaFewShotEvalResult): NiaFewShotEvalDto =
            NiaFewShotEvalDto(
                status = result.status,
                readyForPublish = result.readyForPublish,
                checkedExamples = result.checkedExamples,
                actionCoverage = result.actionCoverage,
                hardAmbiguousCount = result.hardAmbiguousCount,
                failures = result.failures.map { it.toMessage() },
            )
    }
}

private fun NiaFewShotSet.toDto(redactRawText: Boolean): NiaFewShotSetDto =
    NiaFewShotSetDto(
        id = id,
        scope =
            NiaFewShotScopeDto(
                type = scope.type.name,
                guildId = scope.guildId,
                channelId = scope.channelId,
                persona = scope.persona,
            ),
        activeVersion = activeVersion,
        versions = versions.map { it.toDto(redactRawText) },
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

private fun NiaFewShotVersion.toDto(redactRawText: Boolean): NiaFewShotVersionDto =
    NiaFewShotVersionDto(
        id = id,
        setId = setId,
        version = version,
        status = status.name,
        examples = examples.map { it.toDto(redactRawText) },
        createdBy = createdBy,
        reviewedBy = reviewedBy,
        publishedAt = publishedAt,
        rollbackOfVersion = rollbackOfVersion,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

private fun NiaFewShotExample.toDto(redactRawText: Boolean): NiaFewShotExampleDto =
    NiaFewShotExampleDto(
        id = id,
        title = title,
        rawMessages = rawMessages.map { it.toDto(redactRawText) },
        expectedAction = expectedAction.name,
        expectedDeliveryMode = expectedDeliveryMode?.name,
        expectedReplies = expectedReplies,
        badReplies = badReplies,
        currentState = currentState,
        expectedReactionCode = expectedReactionCode,
        expectedReevaluateAfterMs = expectedReevaluateAfterMs,
        reason = reason,
        evidenceRefs = evidenceRefs,
        badAlternative =
            NiaFewShotBadAlternativeDto(
                action = badAlternative.action.name,
                deliveryMode = badAlternative.deliveryMode?.name,
                whyBad = badAlternative.whyBad,
            ),
        tags = tags,
        priority = priority,
        privacyClass = privacyClass.name,
        evalStatus = evalStatus.name,
    )

private fun NiaFewShotRawMessage.toDto(redactRawText: Boolean): NiaFewShotRawMessageDto =
    NiaFewShotRawMessageDto(
        ref = ref,
        authorRole = authorRole,
        offsetMs = offsetMs,
        text = if (redactRawText) "[redacted]" else text,
    )
