package com.discordassistant.central.participation.adapter.inbound.web

import com.discordassistant.central.global.security.DashboardActor
import com.discordassistant.central.participation.application.rag.ConversationRagLibrary
import com.discordassistant.central.participation.application.rag.ConversationRagService
import com.discordassistant.central.participation.domain.model.fewshot.NiaFewShotExample
import com.discordassistant.central.participation.domain.model.rag.ConversationRagEntry
import com.discordassistant.central.participation.domain.model.rag.ConversationRagMatch
import jakarta.servlet.http.HttpServletRequest
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

@RestController
@RequestMapping("/api/admin/nia/conversation-rag")
class ConversationRagAdminController(
    private val service: ConversationRagService,
) {
    @GetMapping
    fun library(httpRequest: HttpServletRequest): ConversationRagLibraryDto {
        DashboardActor.from(httpRequest)
        return service.library().toDto()
    }

    @PutMapping
    fun replace(
        @RequestBody request: ConversationRagReplaceRequest,
        httpRequest: HttpServletRequest,
    ): ConversationRagLibraryDto {
        DashboardActor.from(httpRequest)
        return service.replace(request.examples.map { it.toDomain() }).toDto()
    }

    @PostMapping("/search")
    fun search(
        @RequestBody request: ConversationRagSearchRequest,
        httpRequest: HttpServletRequest,
    ): List<ConversationRagMatchDto> {
        DashboardActor.from(httpRequest)
        return service.search(request.sceneText, request.limit ?: 2).map(ConversationRagMatch::toDto)
    }
}

data class ConversationRagReplaceRequest(
    val examples: List<NiaFewShotExampleDto>,
)

data class ConversationRagSearchRequest(
    val sceneText: String,
    val limit: Int? = null,
)

data class ConversationRagLibraryDto(
    val entries: List<ConversationRagEntryDto>,
    val embeddingModel: String,
    val indexedCount: Int,
    val updatedAt: Instant?,
)

data class ConversationRagEntryDto(
    val id: Long?,
    val example: NiaFewShotExampleDto,
    val indexed: Boolean,
    val embeddingModel: String?,
    val updatedAt: Instant,
)

data class ConversationRagMatchDto(
    val id: Long?,
    val score: Double,
    val scoringMethod: String,
    val example: NiaFewShotExampleDto,
)

private fun ConversationRagLibrary.toDto(): ConversationRagLibraryDto =
    ConversationRagLibraryDto(
        entries = entries.map(ConversationRagEntry::toDto),
        embeddingModel = embeddingModel,
        indexedCount = indexedCount,
        updatedAt = updatedAt,
    )

private fun ConversationRagEntry.toDto(): ConversationRagEntryDto =
    ConversationRagEntryDto(
        id = id,
        example = example.toAdminDto(),
        indexed = embedding != null,
        embeddingModel = embeddingModel,
        updatedAt = updatedAt,
    )

private fun ConversationRagMatch.toDto(): ConversationRagMatchDto =
    ConversationRagMatchDto(
        id = entry.id,
        score = score,
        scoringMethod = scoringMethod.name,
        example = entry.example.toAdminDto(),
    )

private fun NiaFewShotExample.toAdminDto(): NiaFewShotExampleDto =
    NiaFewShotExampleDto(
        id = id,
        title = title,
        rawMessages = rawMessages.map { NiaFewShotRawMessageDto(it.ref, it.authorRole, it.offsetMs, it.text) },
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
                whyBad = badAlternative.whyBad,
                deliveryMode = badAlternative.deliveryMode?.name,
            ),
        tags = tags,
        priority = priority,
        privacyClass = privacyClass.name,
        evalStatus = evalStatus.name,
    )
